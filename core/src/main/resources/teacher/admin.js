const $ = id => document.getElementById(id);
let key = location.hash.slice(1) || sessionStorage.getItem('rythmo-key') || '';
if (location.hash) history.replaceState(null, '', location.pathname);
let state;
function message(text) { $('message').textContent = text; $('message').style.display = 'block'; }
async function api(path, body) {
  const response = await fetch(path, {method: body ? 'POST' : 'GET', headers: {Authorization: 'Bearer '+key, ...(body ? {'Content-Type':'application/json'} : {})}, body: body ? JSON.stringify(body) : undefined});
  if (!response.ok) throw Error(await response.text());
  return response;
}
function option(value, label) { const el=document.createElement('option'); el.value=value; el.textContent=label; return el; }
function classSelected() {
  const cls=state.classes.find(c=>c.id===$('class').value);
  if (!cls) return;
  $('class-name').value=cls.name; $('level').value=cls.level;
  $('pupils').value=cls.pupils.map(p=>[p.firstName,p.lastName,p.sex==='GIRL'?'F':'G'].join(';')).join('\n');
}
function duration(ms) { return Math.floor(ms/60000)+':'+String(Math.floor(ms/1000)%60).padStart(2,'0')+(ms%1000 ? ','+String(ms%1000).padStart(3,'0') : ''); }
function total(r) { return (r.rawCumulativeMs.at(-1)||0)+r.corrections.reduce((sum,c)=>sum+c.correctedMs-c.originalMs,0); }
function results() {
  const session=state.sessions.find(s=>s.id===$('results-session').value); if (!session) return;
  const reports=state.results.filter(r=>r.upload.sessionId===session.id);
  const received=new Set(reports.map(r=>r.upload.runner.pupil.id));
  const missing=session.pupils.filter(p=>!received.has(p.id));
  $('coverage').textContent=reports.length+' / '+session.pupils.length+' bilans reçus'+(missing.length?' · À recevoir : '+missing.map(p=>p.firstName+' '+p.lastName).join(', '):' · Classe complète');
  $('results').replaceChildren();
  for (const result of reports) {
    const r=result.upload.runner, row=document.createElement('tr');
    const claim=state.claims.find(c=>c.groupId===result.upload.groupId);
    for (const value of [r.pupil.firstName+' '+r.pupil.lastName,claim?.deviceName||result.upload.deviceId,r.abandoned?'Abandon':duration(total(r)),result.gradeTenths==null?'Non noté':(result.gradeTenths/10).toLocaleString('fr-FR')+' / '+((session.rubric.maxGradeTenths??200)/10).toLocaleString('fr-FR'),r.corrections.length+' / '+r.rawCumulativeMs.length]) {
      const cell=document.createElement('td'); cell.textContent=value; row.append(cell);
    }
    const cell=document.createElement('td');
    if (result.upload.pdfBase64) {
      const button=document.createElement('button'); button.textContent='PDF'; button.className='secondary';
      button.onclick=async()=>{try { const response=await api('/admin/pdf/'+r.id); const url=URL.createObjectURL(await response.blob()); const a=document.createElement('a'); a.href=url; a.download='rythmo-'+r.id+'.pdf'; a.click(); setTimeout(()=>URL.revokeObjectURL(url),30000); } catch(e) {message(e.message);} };
      cell.append(button);
    }
    row.append(cell); $('results').append(row);
  }
}
async function refresh() {
  try {
    state=await (await api('/admin/state')).json(); sessionStorage.setItem('rythmo-key',key);
    $('access').hidden=true; $('workspace').hidden=false;
    const current=state.sessions.find(s=>s.id===state.activeSessionId);
    $('active').textContent=current?current.schoolClass+' · '+current.distanceMeters+' m'+(current.lapCount?' · '+current.lapCount+' tours identiques':'')+' · '+current.date+' · '+current.rubric.name:'Aucune séance';
    $('pairing').textContent=state.pairingCode;
    $('teacher-code').textContent=state.teacherCode;
    $('counts').textContent=state.devices.length+' appareil(s) connu(s) · '+state.sessions.length+' séance(s) conservée(s)';
    $('devices').replaceChildren();
    for(const d of state.devices) {const p=document.createElement('p'); p.textContent=d.name+' · '+d.lastSync.slice(0,19).replace('T',' '); $('devices').append(p);}
    const oldClass=$('class').value; $('class').replaceChildren(...state.classes.map(c=>option(c.id,c.name)));
    if(oldClass) $('class').value=oldClass; else classSelected();
    const old=$('results-session').value; $('results-session').replaceChildren(...state.sessions.slice().reverse().map(s=>option(s.id,s.schoolClass+' · '+s.distanceMeters+' m · '+s.date+' · '+s.id.slice(0,8))));
    $('results-session').value=state.sessions.some(s=>s.id===old)?old:state.activeSessionId;
    results();
  } catch(e) {message(e.message);}
}
$('login').onclick=()=>{key=$('key').value.trim();refresh();};
$('refresh').onclick=refresh; $('class').onchange=classSelected; $('results-session').onchange=results;
$('date').value=new Date(Date.now()-new Date().getTimezoneOffset()*60000).toISOString().slice(0,10);
$('session-form').onsubmit=async(event)=>{
  event.preventDefault(); $('publish').disabled=true;
  try {
    const cls=state.classes.find(c=>c.id===$('class').value);
    const pupils=$('pupils').value.split('\n').filter(l=>l.trim()).map(line=>{
      const [firstName,lastName,sexValue,...extra]=line.split(';').map(v=>v.trim());
      if(!firstName||!lastName||!['F','G'].includes(sexValue)||extra.length) throw Error('Format attendu : Prénom;Nom;F ou G');
      const sex=sexValue==='F'?'GIRL':'BOY'; const prior=cls?.pupils.find(p=>p.firstName===firstName&&p.lastName===lastName&&p.sex===sex);
      return {...(prior?{id:prior.id}:{}),firstName,lastName,sex};
    });
    if(pupils.length<1||pupils.length>30) throw Error('Une classe doit contenir de 1 à 30 élèves.');
    const rubric={name:$('rubric-name').value,maxGradeTenths:Math.round(Number($('grade-max').value)*10),reference2000Ms:Math.round(Number($('reference').value)*1000),boyPermille:Math.round(Number($('boys').value)*1000),girlPermille:Math.round(Number($('girls').value)*1000),levelsPermille:Object.fromEntries(['6e','5e','4e','3e'].map(l=>[l,Math.round(Number($('coef'+l).value)*1000)]))};
    await api('/admin/session',{schoolClass:$('class-name').value,level:$('level').value,title:$('title').value,date:$('date').value,distanceMeters:Number($('distance').value),lapCount:$('course-mode').value==='laps'?Number($('lap-count').value):null,passageEveryMeters:Number($('passage-meters').value),rubric,pupils});
    message('Nouvelle séance publiée. Synchronisez les appareils avant le départ.'); await refresh();
  }catch(e){message(e.message);}finally{$('publish').disabled=false;}
};
if(key) refresh();
