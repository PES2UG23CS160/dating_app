"""Create three fictional local demo profiles using the real API (idempotent)."""
from smoke import Client
import os
import secrets

password = os.environ.get('DEMO_PASSWORD') or secrets.token_urlsafe(16)
people = [
    ('alex','Alex','Man','1998-04-15','Bengaluru','Weekend trail finder. Amateur cook. I have a playlist for almost everything.','Finding a tiny café, then getting a little lost on purpose.','Coffee, hiking, photography','photo-1500648767791-00dcc994a43e'),
    ('maya','Maya','Woman','1999-09-12','Bengaluru','Designer, book collector, and enthusiastic keeper of slightly dramatic houseplants.','A bookstore, no plans, and a conversation that runs long.','Design, books, pottery','photo-1534528741775-53994a69daeb'),
    ('sam','Sam','Nonbinary','1997-06-20','Pune','Here for good stories, Sunday markets, and finding the best dosa in the city.','Trading our favourite songs over a very long coffee.','Music, cooking, travel','photo-1506794778202-cad84cf45f1d')
]
accounts=[]
assert Client().request('/config')['mode']=='demo', 'Never seed fictional people into a pilot'
for key,name,gender,birth,city,bio,prompt,interests,photo in people:
    client=Client()
    credentials={'email':key+'@unfold.example','password':password}
    try:
        client.request('/auth/register','POST',credentials,expected=201)
    except AssertionError as error:
        if error.args[0][1] != 409:
            raise
        client.request('/auth/login','POST',credentials)
    profile=dict(name=name,birthDate=birth,city=city,gender=gender,interestedIn='Everyone',minAge=18,maxAge=100,bio=bio,prompt=prompt,interests=interests,photoUrl=f'https://images.unsplash.com/{photo}?w=800&auto=format&fit=crop&q=85')
    client.request('/me/profile','PUT',profile)
    accounts.append((client,client.request('/me')['id']))
# Maya has already liked Alex, so Alex can demonstrate a mutual match.
accounts[1][0].request(f'/profiles/{accounts[0][1]}/decision','POST',{'liked':True})
print('Fictional demo profiles created. Sign in as alex@unfold.example')
print('Demo password (save locally):',password)
