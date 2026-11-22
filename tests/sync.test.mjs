import test from 'node:test';
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
if(!globalThis.crypto)globalThis.crypto=webcrypto;
import worker from '../server/cloudflare/worker.mjs';

test('one sync answer carries the badges, their tag and the directory of profiles',async()=>{
  const env={PROFILES:{prepare(sql){return {
    bind(){return this;},
    async all(){
      if(/FROM profiles WHERE metadata IS NOT NULL/.test(sql))return {results:[{uid:'111',updated_at:5}]};
      return {results:[]};
    },
    async first(){return null;},async run(){return {meta:{changes:0}};},
  };}}};
  const response=await worker.fetch(new Request('https://mod/v1/sync',{headers:{'user-agent':'ttcuz'}}),env,{waitUntil(){}});
  assert.equal(response.status,200);
  const body=await response.json();
  assert.deepEqual(body.profiles,{'111':5});
  assert.ok(Array.isArray(body.badges.badges) && body.badges.badges[0].id==='creator_trem0r1_1');
  assert.match(body.badgesTag,/^"[0-9a-f]{24}"$/);
  // the same badges give the same tag, as /badges sends it
  const again=await (await worker.fetch(new Request('https://mod/badges'),env,{waitUntil(){}})).headers.get('etag');
  assert.equal(again,body.badgesTag);
});
