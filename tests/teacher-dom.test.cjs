const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {JSDOM} = require('jsdom');
const root = path.resolve(__dirname, '../core/src/main/resources/teacher');
const key = '11111111-2222-4333-8444-555555555555';
const tick = async () => { for (let i=0;i<5;i++) await new Promise(setImmediate); };
function fixture() {
  const pupils = ['Alice','Basile','Chloe','David'].map((firstName,i)=>({id:'p'+i,firstName,lastName:'Exemple',sex:i%2?'BOY':'GIRL'}));
  const rubric = {name:'Fictional',maxGradeTenths:200,reference2000Ms:600000,boyPermille:1000,girlPermille:1100,levelsPermille:{'6e':1000,'5e':950,'4e':900,'3e':850}};
  const session = {id:'session',title:'Course fictive',schoolClass:'6e Test',level:'6e',date:'2026-09-21',distanceMeters:1000,lapCount:6,passageEveryMeters:400,pupils,rubric};
  const runner = (id,abandoned) => ({id:'r'+id,pupil:pupils[id],abandoned,rawCumulativeMs:[80000,160123],corrections:[],cancelledPassages:[]});
  return {classes:[{id:'class',name:'6e Test',level:'6e',pupils}], sessions:[session], activeSessionId:'session', devices:[{name:'Tablet',lastSync:'2026-09-21T10:00:00'}],pairingCode:'fictional',teacherCode:'fictional',claims:[{sessionId:'session',groupId:'group',pupilIds:['p1','p2','p3'],deviceName:'Tablet'}],results:[{upload:{sessionId:'session',groupId:'group',deviceId:'device',runner:runner(2,false),pdfBase64:'fictional'},pdfReady:true,gradeTenths:150},{upload:{sessionId:'session',groupId:'group',deviceId:'device',runner:runner(3,true)},pdfReady:false,gradeTenths:null}]};
}
async function page(options={}) {
  const dom = new JSDOM(fs.readFileSync(path.join(root,'index.html'),'utf8'),{url:'http://localhost/?tab=reception#'+(options.noKey?'':key),runScripts:'outside-only',pretendToBeVisual:true});
  const w=dom.window, calls=[], timers=new Map(); let sequence=0, state=fixture(), failed=false, hold=null;
  w.setTimeout=(callback,delay)=>{timers.set(++sequence,{callback,delay});return sequence;};w.clearTimeout=id=>timers.delete(id);
  w.URL.createObjectURL=()=> 'blob:fictional'; w.URL.revokeObjectURL=()=>{};w.HTMLAnchorElement.prototype.click=function(){};
  w.crypto.randomUUID=()=> 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee';
  w.fetch=async (url, init)=> {
    calls.push({url,init}); if(hold) await hold;
    if(failed) return {ok:false,text:async()=> 'Network unavailable'};
    let data=state;
    if(url==='/admin/import/preview') data={sheets:[{name:'Values',rows:[['Alice','Exemple','F'],['Basile','Exemple','M']],ignoredFormulas:1}]};
    if(url==='/admin/classes') data={...JSON.parse(init.body),id:'imported'};
    if(url==='/admin/preparation/validate') data=JSON.parse(init.body);
    return {ok:true,json:async()=>structuredClone(data),blob:async()=>new w.Blob(['%PDF-fictional'])};
  };
  for(const name of ['preparation.js','admin.js']) new vm.Script(fs.readFileSync(path.join(root,name),'utf8'),{filename:path.join(root,name)}).runInContext(dom.getInternalVMContext());
  await tick();
  return {w, $:id=>w.document.getElementById(id),calls,timers,state,setState:value=>{state=value;},fail:value=>{failed=value;},hold:value=>{hold=value;},close:()=>w.close()};
}

test('console renders reception states, accessible tabs, search, reports and PDF download',async()=>{
 const p=await page();try {
  assert.equal(p.$('access').hidden,true);
  assert.match(p.$('reception-counts').textContent,/Non affecté : 1.*Bilan attendu : 1.*Reçu : 1.*Abandon : 1/);
  p.$('reception-filter').value='Abandon';p.$('reception-filter').onchange();assert.equal(p.$('reception-rows').children.length,1);
  p.$('reception-search').value='missing';p.$('reception-search').oninput();assert.equal(p.$('reception-rows').children.length,0);
  for(const key of ['ArrowRight','ArrowLeft','Home','End','Escape']) p.$('tab-reception').onkeydown({key,preventDefault(){}});
  p.$('tab-results').onclick();assert.equal(p.$('tab-results').getAttribute('aria-selected'),'true');
  assert.match(p.$('results').textContent,/Sans PDF · abandon/);
  await p.$('results').querySelector('button').onclick();assert.equal(p.calls.at(-1).url,'/admin/pdf/r2');
  p.fail(true);await p.$('results').querySelector('button').onclick();assert.match(p.$('message').textContent,/Network unavailable/);
  p.$('message').querySelector('button').onclick();assert.equal(p.$('message').style.display,'none');
 }finally{p.close();}
});
test('refresh preserves displayed records and draft on failure and suppresses concurrent requests',async()=>{
 const p=await page();try {
  const before=p.$('results').textContent;p.$('title').value='Draft retained';p.$('session-form').dispatchEvent(new p.w.Event('input'));
  p.fail(true);await p.$('refresh').onclick();assert.equal(p.$('results').textContent,before);assert.equal(p.$('title').value,'Draft retained');
  p.fail(false);let release;p.hold(new Promise(r=>{release=r;}));const count=p.calls.length;
  const first=p.$('refresh').onclick();await p.$('refresh').onclick();assert.equal(p.calls.length,count+1);release();await first;p.hold(null);
  const poll=[...p.timers.values()].find(t=>t.delay===5000);assert.ok(poll);await poll.callback();assert.equal(p.$('title').value,'Draft retained');
  Object.defineProperty(p.w.document,'hidden',{value:true,configurable:true});p.w.document.dispatchEvent(new p.w.Event('visibilitychange'));assert.equal([...p.timers.values()].filter(t=>t.delay===5000).length,0);
  p.setState({...p.state,sessions:[],activeSessionId:null,classes:[]});await p.$('refresh').onclick();assert.match(p.$('coverage').textContent,/Aucune séance/);
 }finally{p.close();}
});
test('login rejects missing keys and accepts a valid teacher key without preserving URL fragment',async()=>{
 const p=await page({noKey:true});try {
  p.$('key').value='bad';p.$('login').onclick();await tick();assert.equal(p.calls.length,0);assert.match(p.$('message').textContent,/Clé enseignant invalide/);
  p.$('key').value=key;p.$('login').onclick();await tick();assert.equal(p.$('workspace').hidden,false);assert.equal(p.w.location.hash,'');
 }finally{p.close();}
});
test('retained preparation handlers validate class imports, preserve IDs and reject oversized input',async()=>{
 const p=await page();try {
  p.$('new-class').onclick();p.$('class-name').value='6e Fiction';p.$('pupils').value='Alice;Exemple;F';await p.$('save-class').onclick();assert.equal(p.$('class').value,'imported');
  const file={size:10,name:'fiction.csv',arrayBuffer:async()=>new Uint8Array([65,66]).buffer};
  await p.$('class-file').onchange({target:{files:[file],value:'selected'}});assert.equal(p.$('class-mapping').hidden,false);
  p.$('class-first-row').value='1';p.$('apply-class').onclick();assert.match(p.$('pupils').value,/Alice;Exemple;F/);
  p.$('class-first-row').value='0';p.$('apply-class').onclick();assert.match(p.$('message').textContent,/Première ligne invalide/);
  p.$('class-first-row').value='1';p.$('map-sex').value=p.$('map-first').value;p.$('apply-class').onclick();assert.match(p.$('message').textContent,/colonnes différentes/);
  await p.$('class-file').onchange({target:{files:[{size:2000001}],value:''}});assert.match(p.$('message').textContent,/moins de 2 Mo/);
  p.$('rubric-mode').value='assessment';p.$('fictional-rubric').onclick();p.$('session-form').dispatchEvent(new p.w.Event('change'));assert.match(p.$('rubric-summary').textContent,/Total sur 15/);
  p.$('course-mode').value='distance';p.$('session-form').dispatchEvent(new p.w.Event('input'));assert.equal(p.$('passage-field').hidden,false);
  p.$('edit-draft').onclick();assert.equal(p.$('devices-panel').hidden,false);
 }finally{p.close();}
});
test('portable preparation import restores assessments and rejects malformed or oversized files',async()=>{
 const p=await page();try {
  const importValue=async value=>p.$('import-preparation').onchange({target:{files:[{size:100,text:async()=>JSON.stringify(value)}],value:'selected'}});
  await importValue({schemaVersion:1,session:p.state.sessions[0]});assert.equal(p.$('title').value,'Course fictive');
  const assessment={name:'Fictional table',performanceMaxTenths:100,comparisonMaxTenths:50,comparisonThresholdMs:0,tables:{BOY:[{timeMs:300000,pointsTenths:100}],GIRL:[{timeMs:330000,pointsTenths:100}]}};
  await importValue({schemaVersion:1,session:{...p.state.sessions[0],assessment}});assert.equal(p.$('table-girls').value,'5:30;10');
  await p.$('export-preparation').onclick();assert.equal(p.calls.at(-1).url,'/admin/preparation/validate');
  await p.$('session-form').onsubmit({preventDefault(){}});await p.$('publish').onclick();assert.ok(p.calls.some(c=>c.url==='/admin/session'));
  await p.$('import-preparation').onchange({target:{files:[{size:3000000}],value:''}});assert.match(p.$('message').textContent,/volumineuse/);
 }finally{p.close();}
});
