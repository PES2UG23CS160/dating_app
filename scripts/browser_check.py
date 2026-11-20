import urllib.request,json,socket,base64,os,struct,time
pages=json.load(urllib.request.urlopen('http://localhost:9223/json'))
url=next(p for p in pages if p['type']=='page')['webSocketDebuggerUrl']; path='/' + url.split('/',3)[3]
s=socket.create_connection(('localhost',9223),timeout=15); key=base64.b64encode(os.urandom(16)).decode()
s.sendall(f'GET {path} HTTP/1.1\r\nHost: localhost:9223\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n'.encode())
buf=b''
while b'\r\n\r\n' not in buf: buf+=s.recv(1)
assert b'101' in buf,buf
seq=0

def read(n):
 b=b''
 while len(b)<n:
  part=s.recv(n-len(b))
  if not part:raise RuntimeError('Browser socket closed')
  b+=part
 return b

def call(method,params={}):
 global seq
 seq+=1; payload=json.dumps(dict(id=seq,method=method,params=params)).encode();mask=os.urandom(4);n=len(payload)
 hdr=bytes([129,128+n]) if n<126 else bytes([129,254])+struct.pack('!H',n)
 s.sendall(hdr+mask+bytes(b^mask[i%4] for i,b in enumerate(payload)))
 while True:
  h=read(2);size=h[1]&127
  if size==126:size=struct.unpack('!H',read(2))[0]
  if size==127:size=struct.unpack('!Q',read(8))[0]
  result=json.loads(read(size))
  if result.get('id')==seq:
   assert 'error' not in result,result
   return result.get('result',{})
def js(expression):
 r=call('Runtime.evaluate',dict(expression=expression,returnByValue=True,awaitPromise=True))
 assert 'exceptionDetails' not in r,r
 return r.get('result',{}).get('value')
def shot(name):
 image=call('Page.captureScreenshot',{'format':'png'})['data'];open('docs/'+name+'.png','wb').write(base64.b64decode(image))

import sys
sys.path.insert(0, 'scripts')
from smoke import Client,profile
from pathlib import Path
clients=[]
try:
 stamp=str(time.time_ns());creds=[];ids=[]
 for i,name in enumerate(['Jamie','Robin']):
  client=Client();cred={'email':f'browser-{stamp}-{i}@example.test','password':'Browser-test-password-42'}
  account=client.request('/auth/register','POST',cred,201);clients.append(client);creds.append(cred);ids.append(account['id'])
  client.request('/me/profile','PUT',profile(name))
 clients[0].request(f'/profiles/{ids[1]}/decision','POST',{'liked':True})
 clients[1].request(f'/profiles/{ids[0]}/decision','POST',{'liked':True})
 conv=clients[0].request('/matches')[0]['conversation_id'];prefix=f'/conversations/{conv}'
 call('Page.enable')
 call('Emulation.setDeviceMetricsOverride',dict(width=1440,height=1000,deviceScaleFactor=1,mobile=False))
 call('Page.navigate',{'url':'http://localhost:5173'})
 time.sleep(2)
 js('document.querySelector(".text-button").click()');time.sleep(.2)
 js('document.querySelector("input[name=email]").value='+json.dumps(creds[0]['email'])+';document.querySelector("input[name=password]").value='+json.dumps(creds[0]['password'])+';document.querySelector("form").requestSubmit()')
 time.sleep(1)
 assert js('document.querySelector(".avatar")!==null')
 js('Array.from(document.querySelectorAll("nav button")).find(b=>b.textContent.includes("Connections")).click()');time.sleep(.5)
 js('document.querySelector(".connection .primary").click()');time.sleep(.5)
 # Use React's native setter path to exercise the controlled form.
 def fill(selector,value):
  js('(()=>{const e=document.querySelector('+json.dumps(selector)+');Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,"value").set.call(e,'+json.dumps(value)+');e.dispatchEvent(new Event("input",{bubbles:true}));})()')
 fill('#chat-text','Hello Robin! What has made you smile this week?')
 js('document.querySelector(".message-form").requestSubmit()');time.sleep(.6)
 assert len(clients[1].request(prefix+'/messages'))==1
 clients[1].request(prefix+'/messages','POST',{'clientId':str(__import__('uuid').uuid4()),'body':'A long walk, a good book, and discovering this conversation.'})
 time.sleep(1)
 assert js('document.body.innerText.includes("A long walk, a good book")'), 'WebSocket did not refresh peer message'
 js('document.querySelector(".reveal-panel .primary").click()');time.sleep(.4)
 clients[1].request(prefix+'/activity/accept','POST',{});time.sleep(.5)
 fill('.answer-form textarea','Coffee, a bookshop, and a long walk without a schedule.')
 js('document.querySelector(".answer-form").requestSubmit()');time.sleep(.5)
 assert 'Coffee, a bookshop' not in json.dumps(clients[1].request(prefix+'/activity'))
 clients[1].request(prefix+'/activity/answer','POST',{'round':0,'body':'Making breakfast together, then exploring somewhere new.'});time.sleep(.8)
 assert js('document.body.innerText.includes("Making breakfast together")')
 assert js('document.documentElement.scrollWidth<=innerWidth')
 shot('chat-desktop')
 call('Emulation.setDeviceMetricsOverride',dict(width=390,height=844,deviceScaleFactor=1,mobile=True));time.sleep(.3)
 assert js('document.documentElement.scrollWidth<=innerWidth')
 shot('chat-mobile')
 assert js('sessionStorage.getItem("unfold-token")===null')
 assert not js('document.cookie.includes("unfold_session")'), 'Session cookie exposed to JS'
 print('PASS: real UI login, chat submission, live peer updates, reveal privacy and live reveal, mobile overflow, HttpOnly session.')
finally:
 for client in clients:
  try:client.request('/me','DELETE')
  except Exception as e:print('Browser fixture cleanup:',e)
 try:call('Browser.close')
 except Exception:pass
