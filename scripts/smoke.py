"""Integration checks against a running DEMO stack. Creates and deletes its own accounts."""
import http.cookiejar
import json
import os
import time
import urllib.request
import urllib.error
import uuid
from concurrent.futures import ThreadPoolExecutor
BASE = os.environ.get('UNFOLD_URL', 'http://localhost:5173') + '/api'
class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf = None
    def request(self, path, method='GET', data=None, expected=200, csrf=True):
        if method != 'GET' and csrf and not self.csrf:
            self.csrf = self.request('/csrf')['token']
        headers = {'Content-Type': 'application/json'}
        if method != 'GET' and csrf:
            headers['X-XSRF-TOKEN'] = self.csrf
        req = urllib.request.Request(BASE + path, data=None if data is None else json.dumps(data).encode(), headers=headers, method=method)
        time.sleep(.12)  # Respect the production proxy's burst limit.
        try:
            with self.opener.open(req, timeout=30) as response:
                status, body = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, body = error.code, error.read()
        if status == 401 or path == '/auth/logout':
            self.csrf = None
        assert status == expected, (path, status, expected, body.decode()[:500])
        return json.loads(body) if body and body[:1] in (b'{', b'[') else None

def profile(name):
    return dict(name=name,birthDate='2000-01-01',city='Bengaluru',gender='Nonbinary',interestedIn='Everyone',minAge=18,maxAge=100,bio='Fictional integration test.',prompt='A walk and coffee.',interests='Books, coffee',photoUrl='')

if __name__ == '__main__':
    anon=Client()
    assert anon.request('/config')['mode']=='demo', 'Only run this fixture suite against a demo deployment.'
    anon.request('/health');anon.request('/discover',expected=401)
    anon.request('/auth/register','POST',{'email':'unused@example.test','password':'test-password-42'},expected=403,csrf=False)
    stamp=str(time.time_ns());clients=[];ids=[];credentials=[]
    try:
        for i in range(3):
            c=Client();cred=dict(email=f'smoke-{stamp}-{i}@example.test',password='Smoke-test-password-42')
            me=c.request('/auth/register','POST',cred,201);clients.append(c);ids.append(me['id']);credentials.append(cred)
            assert 'token' not in me
            c.request('/auth/register','POST',cred,409)
            c.request('/auth/login','POST',{**cred,'password':'incorrect-password'},401)
            c.request('/auth/login','POST',cred)
            c.request('/me/profile','PUT',{**profile(f'Test {i}'),'birthDate':'2020-01-01'},400)
            c.request('/me/profile','PUT',{**profile(f'Test {i}'),'minAge':50,'maxAge':20},400)
            c.request('/me/profile','PUT',profile(f'Test {i}'))
        a,b,outsider=clients
        a.request('/admin',expected=403)
        discovery=a.request('/discover')
        assert any(p['id']==ids[1] for p in discovery)
        assert all('birth_date' not in p and 'email' not in p for p in discovery)
        b.request('/me/profile','PUT',{**profile('Test 1'),'interestedIn':'Woman'})
        assert not any(p['id']==ids[1] for p in a.request('/discover'))
        b.request('/me/profile','PUT',profile('Test 1'))
        a.request(f'/profiles/{ids[0]}/decision','POST',{'liked':True},400)
        with ThreadPoolExecutor(max_workers=2) as pool:
            outcomes=list(pool.map(lambda i:clients[i].request(f'/profiles/{ids[1-i]}/decision','POST',{'liked':True}),range(2)))
        assert any(o['matched'] for o in outcomes)
        conv=next(p['conversation_id'] for p in a.request('/matches') if p['id']==ids[1]);path=f'/conversations/{conv}'
        outsider.request(path+'/messages',expected=404)
        msg={'clientId':str(uuid.uuid4()),'body':'Hello from the test'}
        first=a.request(path+'/messages','POST',msg)
        assert a.request(path+'/messages','POST',msg)['id']==first['id']
        a.request(path+'/messages','POST',{**msg,'body':'Changed retry'},409)
        b.request(path+'/messages','POST',{'clientId':str(uuid.uuid4()),'body':'Hi back'})
        assert len(b.request(path+'/messages'))==2
        a.request(path+'/activity/propose','POST',{})
        a.request(path+'/activity/answer','POST',{'round':0,'body':'Too soon'},409)
        b.request(path+'/activity/accept','POST',{})
        a.request(path+'/activity/answer','POST',{'round':0,'body':'PRIVATE-ANSWER-ONE'})
        assert 'PRIVATE-ANSWER-ONE' not in json.dumps(b.request(path+'/activity'))
        a.request(path+'/activity/answer','POST',{'round':0,'body':'Changed'},409)
        result=b.request(path+'/activity/answer','POST',{'round':0,'body':'PRIVATE-ANSWER-TWO'})
        assert 'PRIVATE-ANSWER-ONE' in json.dumps(result) and 'PRIVATE-ANSWER-TWO' in json.dumps(result)
        a.request(path+'/activity/answer','POST',{'round':1,'body':'NEVER-REVEAL-ME'})
        b.request(path+'/activity/skip','POST',{'round':1})
        assert 'NEVER-REVEAL-ME' not in json.dumps(a.request(path+'/activity'))
        a.request(path+'/activity/answer','POST',{'round':1,'body':'Stale request'},409)
        with ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(lambda i:clients[i].request(path+'/activity/answer','POST',{'round':2,'body':f'Concurrent {i}'}),range(2)))
        assert a.request(path+'/activity')['status']=='complete'
        b.request(f'/profiles/{ids[0]}/report','POST',{'reason':'TEST report with selected evidence','messageId':first['id']})
        outsider.request(f'/profiles/{ids[0]}/report','POST',{'reason':'Cannot attach private messages','messageId':first['id']},400)
        b.request(f'/profiles/{ids[0]}/block','POST',{})
        a.request(path+'/messages',expected=404)
        a.request(path+'/messages','POST',{'clientId':str(uuid.uuid4()),'body':'Blocked'},404)
        assert not any(p['id']==ids[1] for p in a.request('/matches'))
        a.request(f'/profiles/{ids[2]}/decision','POST',{'liked':True})
        outsider.request(f'/profiles/{ids[0]}/decision','POST',{'liked':True})
        conv2=next(p['conversation_id'] for p in a.request('/matches') if p['id']==ids[2])
        a.request(f'/conversations/{conv2}/unmatch','POST',{})
        outsider.request(f'/conversations/{conv2}/messages',expected=410)
        stolen=Client()
        for cookie in a.jar:stolen.jar.set_cookie(cookie)
        a.request('/auth/logout','POST',{})
        stolen.request('/me',expected=401)
        a.request('/auth/login','POST',credentials[0])
        print('PASS: cookies/CSRF, auth, validation, reciprocal discovery, concurrency, chat idempotency, reveal privacy/skip/races, membership, report evidence, blocking, unmatch and session revocation.')
    finally:
        for c in clients:
            try:c.request('/me','DELETE');c.request('/me',expected=401)
            except Exception as e:print('Fixture cleanup needs attention:', str(e)[:180])
