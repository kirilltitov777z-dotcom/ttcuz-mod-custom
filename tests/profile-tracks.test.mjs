import test from 'node:test';
import assert from 'node:assert/strict';
import {normalizeTracks} from '../server/cloudflare/profiles.mjs';

test('profile tracks: TikTok sound ids with a title and an artist, at most 12, nothing else kept', () => {
  assert.deepEqual(normalizeTracks(undefined), []);
  assert.deepEqual(normalizeTracks([{id:'7300000000000000001',title:' Devil Town ',artist:'Cavetown',link:'https://x'}]),
    [{id:'7300000000000000001',title:'Devil Town',artist:'Cavetown'}]);
  for (const bad of [[{id:'abc'}], [{id:'1'},{id:'1'}], Array.from({length:13},(_,i)=>({id:String(i+1)})),
    [{id:'1',title:'x'.repeat(121)}], [{id:'1',artist:'a\nb'}], 'list', [null]])
    assert.throws(() => normalizeTracks(bad));
});
