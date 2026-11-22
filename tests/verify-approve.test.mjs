import test from 'node:test';
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
if(!globalThis.crypto)globalThis.crypto=webcrypto;
import {profileRoute,challenge,digest} from '../server/cloudflare/profiles.mjs';

// a small D1 stand-in: the pending attempt and the profile row, by the statements the route runs
function memory(){
  const pending=new Map(),profiles=new Map(),limits={hits:0};
  return {pending,profiles,PROFILES:{prepare(sql){let args=[];return {
    bind(...a){args=a;return this;},
    async run(){
      if(/INSERT OR REPLACE INTO verify_pending/.test(sql))pending.set(args[0],{token_hash:args[1],code:args[2],at:args[3]});
      if(/DELETE FROM verify_pending/.test(sql))pending.delete(args[0]);
      if(/INSERT INTO profiles/.test(sql))profiles.set(args[0],{owner_hash:args[1],verified:1});
      return {meta:{changes:1}};
    },
    async first(){
      if(/INTO limits/.test(sql))return {hits:++limits.hits};
      if(/FROM verify_pending/.test(sql))return pending.get(args[0])||null;
      if(/FROM profiles/.test(sql))return profiles.get(args[0])||null;
      return null;
    },
    async all(){return {results:[]};},
  };}}};
}
const KEY='k'.repeat(40);
const post=(path,body,headers={})=>new Request('https://mod'+path,{method:'POST',headers:{'content-type':'application/json',...headers},body:JSON.stringify(body)});

test('the bot hands an account to the phone whose own failed attempt showed this code',async()=>{
  const env={...memory(),BADGE_BOT_KEY:KEY};
  const uid='123456789',token='a'.repeat(64);
  globalThis.fetch=async()=>{throw new Error('offline');};
  // the reflashed phone's own attempt fails (no bio code reachable): it is kept
  const failed=await profileRoute(post('/v1/verify',{uid,token,handle:'friend'}),env);
  assert.equal(failed.status,403);
  const code=await challenge(uid,token);
  assert.equal(env.pending.get(uid).code,code);
  // a wrong code, or no bot key, approves nothing
  assert.equal((await profileRoute(post('/v1/verify/approve',{targetUid:uid,code:'ttcuz-0000000000000000'},{'x-bot-key':KEY}),env)).status,404);
  assert.equal((await profileRoute(post('/v1/verify/approve',{targetUid:uid,code}),env)).status,404);
  assert.equal(env.profiles.size,0);
  // the right code with the key: the account is this phone's, verified
  const ok=await profileRoute(post('/v1/verify/approve',{targetUid:uid,code},{'x-bot-key':KEY}),env);
  assert.equal(ok.status,200);
  assert.deepEqual(env.profiles.get(uid),{owner_hash:await digest(token),verified:1});
  assert.equal(env.pending.has(uid),false);
});
