function validatedTeacherKey(value) {
  if (typeof value !== 'string') return '';
  const candidate = value.trim();
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(candidate) ? candidate.toLowerCase() : '';
}
function courseSummary(distance, mode, passage, laps) {
  if (mode === 'laps') return `${distance} m en ${laps} tours identiques`;
  const full = Math.floor(distance / passage), remainder = distance % passage;
  return `${distance} m = ${full} × ${passage} m` + (remainder ? ` + ${remainder} m` : '');
}
function receptionRows(state, session) {
  return session.pupils.map(pupil => {
    const claim = state.claims.find(c => c.sessionId === session.id && c.pupilIds.includes(pupil.id));
    const report = state.results.find(r => r.upload.sessionId === session.id && r.upload.runner.pupil.id === pupil.id);
    return {pupil, device: claim?.deviceName || '—', status: report ? (report.upload.runner.abandoned ? 'Abandon' : 'Reçu') : claim ? 'Bilan attendu' : 'Non affecté'};
  });
}
function receptionCounts(rows) {
  return Object.fromEntries(['Non affecté', 'Bilan attendu', 'Reçu', 'Abandon'].map(status => [status, rows.filter(r => r.status === status).length]));
}
function readDraft(storage, name) {
  try { const value = JSON.parse(storage.getItem(name)); return value && typeof value === 'object' && !Array.isArray(value) ? value : {}; }
  catch { return {}; }
}
if (typeof module !== 'undefined') module.exports = {courseSummary, receptionRows, receptionCounts, readDraft, validatedTeacherKey};

if (typeof document !== 'undefined') {
const $ = id => document.getElementById(id);
let key = validatedTeacherKey(location.hash.slice(1)) || validatedTeacherKey(sessionStorage.getItem('rythmo-key'));
if (!key) sessionStorage.removeItem('rythmo-key');
if (location.hash) history.replaceState(null, '', location.pathname);
let state, refreshing = false, publishing = false, draftLoaded = false, selectedTab = 'devices', poll;
const draftName = 'rythmo-session-draft-v1';
const tabNames = ['devices', 'session', 'publication', 'reception', 'results'];
const fields = [...$('session-form').querySelectorAll('input,select,textarea')];
function message(text) { $('message').textContent = text; $('message').style.display = 'block'; }
async function api(path, body) {
  if (!validatedTeacherKey(key)) throw Error("Clé enseignant invalide.");
  const response = await fetch(path, {method: body ? 'POST' : 'GET', headers: {Authorization: 'Bearer '+key, ...(body ? {'Content-Type':'application/json'} : {})}, body: body ? JSON.stringify(body) : undefined});
  if (!response.ok) throw Error(await response.text());
  return response;
}
function option(value, label) { const el=document.createElement('option'); el.value=value; el.textContent=label; return el; }
function saveDraft() {
  try { localStorage.setItem(draftName, JSON.stringify(Object.fromEntries(fields.map(f => [f.id, f.value])))); }
  catch { message('Le navigateur ne peut pas conserver le brouillon après fermeture. Gardez cette page ouverte.'); }
}
function restoreDraft() {
  const draft = readDraft(localStorage, draftName);
  fields.forEach(f => { if (typeof draft[f.id] === 'string') f.value = draft[f.id]; });
}
function classSelected() {
  const cls=state.classes.find(c=>c.id===$('class').value);
  if (!cls) return;
  $('class-name').value=cls.name; $('level').value=cls.level;
  $('pupils').value=cls.pupils.map(p=>[p.firstName,p.lastName,p.sex==='GIRL'?'F':'G'].join(';')).join('\n');
}
function describeCourse() {
  const equal = $('course-mode').value === 'laps';
  $('passage-field').hidden = equal; $('passage-meters').disabled = equal;
  $('laps-field').hidden = !equal; $('lap-count').disabled = !equal;
  $('course-summary').textContent = courseSummary(Number($('distance').value), $('course-mode').value, Number($('passage-meters').value), Number($('lap-count').value));
}
function publicationSummary() {
  $('publication-summary').replaceChildren();
  for (const text of [
    $('title').value, `${$('class-name').value} · ${$('level').value} · ${$('date').value}`,
    $('course-summary').textContent, `${$('pupils').value.split('\n').filter(l => l.trim()).length} élèves`,
    `${$('rubric-name').value} · note sur ${$('grade-max').value} · référence 2000 m : ${$('reference').value} s`,
    `Coefficients garçons ${$('boys').value}, filles ${$('girls').value} ; 6e ${$('coef6e').value}, 5e ${$('coef5e').value}, 4e ${$('coef4e').value}, 3e ${$('coef3e').value}`,
  ]) { const p = document.createElement('p'); p.textContent = text; $('publication-summary').append(p); }
}
function schedulePoll() {
  clearTimeout(poll);
  if (!document.hidden && ['reception', 'results'].includes(selectedTab) && state) poll = setTimeout(async () => { await refresh(); schedulePoll(); }, 5000);
}
function selectTab(name, focus = false) {
  selectedTab = name;
  for (const id of tabNames) {
    $(id+'-panel').hidden = id !== name;
    $('tab-'+id).setAttribute('aria-selected', String(id === name));
    $('tab-'+id).tabIndex = id === name ? 0 : -1;
  }
  if (name === 'publication') publicationSummary();
  if (focus) $('tab-'+name).focus();
  schedulePoll();
}
for (const [index, name] of tabNames.entries()) {
  $('tab-'+name).onclick = () => selectTab(name);
  $('tab-'+name).onkeydown = event => {
    let next;
    if (event.key === 'ArrowRight') next = (index+1)%tabNames.length;
    if (event.key === 'ArrowLeft') next = (index+tabNames.length-1)%tabNames.length;
    if (event.key === 'Home') next = 0;
    if (event.key === 'End') next = tabNames.length-1;
    if (next !== undefined) { event.preventDefault(); selectTab(tabNames[next], true); }
  };
}
document.addEventListener('visibilitychange', schedulePoll);
function duration(ms) { return Math.floor(ms/60000)+':'+String(Math.floor(ms/1000)%60).padStart(2,'0')+(ms%1000 ? ','+String(ms%1000).padStart(3,'0') : ''); }
function total(r) { return (r.rawCumulativeMs.at(-1)||0)+r.corrections.reduce((sum,c)=>sum+c.correctedMs-c.originalMs,0); }
function appendCells(row, values) { for (const value of values) { const cell=document.createElement('td'); cell.textContent=value; row.append(cell); } }
function countsText(rows) { return Object.entries(receptionCounts(rows)).map(([label, count]) => `${label} : ${count}`).join(' · '); }
function reception() {
  const session = state.sessions.find(s => s.id === $('reception-session').value);
  $('reception-rows').replaceChildren();
  if (!session) { $('reception-counts').textContent = 'Aucune séance publiée.'; return; }
  const rows = receptionRows(state, session);
  $('reception-counts').textContent = countsText(rows);
  const filter = $('reception-filter').value, search = $('reception-search').value.toLocaleLowerCase('fr');
  for (const r of rows) {
    const name = r.pupil.firstName+' '+r.pupil.lastName;
    if ((filter && r.status !== filter) || !name.toLocaleLowerCase('fr').includes(search)) continue;
    const row = document.createElement('tr'); appendCells(row, [name, r.device, r.status]); $('reception-rows').append(row);
  }
}
function results() {
  const session=state.sessions.find(s=>s.id===$('results-session').value);
  $('results').replaceChildren();
  if (!session) { $('coverage').textContent = 'Aucune séance publiée.'; return; }
  const reports=state.results.filter(r=>r.upload.sessionId===session.id);
  $('coverage').textContent = countsText(receptionRows(state, session));
  for (const result of reports) {
    const r=result.upload.runner, row=document.createElement('tr');
    const claim=state.claims.find(c=>c.groupId===result.upload.groupId);
    const cancellations = (r.cancelledPassages || []).map(c => `Passage ${c.number} annulé : ${duration(c.cumulativeMs)} (${c.cancelledAt})`).join(' ; ');
    const corrections = r.corrections.map(c => `Passage ${c.number} : ${duration(c.originalMs)} → ${duration(c.correctedMs)} (${c.at.slice(0,19).replace('T',' ')})`).join(' ; ') || 'Aucune';
    appendCells(row, [r.pupil.firstName+' '+r.pupil.lastName,claim?.deviceName||result.upload.deviceId,r.abandoned?'Abandon · '+duration(total(r))+' enregistré':duration(total(r)),result.gradeTenths==null?'Non noté':(result.gradeTenths/10).toLocaleString('fr-FR')+' / '+((session.rubric.maxGradeTenths??200)/10).toLocaleString('fr-FR'),[corrections,cancellations].filter(Boolean).join(' ; ')]);
    const cell=document.createElement('td');
    if (result.upload.pdfBase64) {
      const button=document.createElement('button'); button.textContent='PDF'; button.className='secondary';
      button.setAttribute('aria-label', `Télécharger le PDF de ${r.pupil.firstName} ${r.pupil.lastName}`);
      button.onclick=async()=>{try { const response=await api('/admin/pdf/'+r.id); const url=URL.createObjectURL(await response.blob()); const a=document.createElement('a'); a.href=url; a.download='rythmo-'+r.id+'.pdf'; a.click(); setTimeout(()=>URL.revokeObjectURL(url),30000); } catch(e) {message(e.message);} };
      cell.append(button);
    } else cell.textContent = r.abandoned ? 'Sans PDF · abandon' : 'PDF indisponible';
    row.append(cell); $('results').append(row);
  }
}
async function refresh() {
  if (refreshing || publishing) return;
  refreshing = true;
  try {
    const received = await (await api('/admin/state')).json();
    state = received;
    sessionStorage.setItem('rythmo-key',validatedTeacherKey(key));
    $('access').hidden=true; $('workspace').hidden=false;
    const current=state.sessions.find(s=>s.id===state.activeSessionId);
    $('active').textContent=current?current.schoolClass+' · '+current.distanceMeters+' m'+(current.lapCount?' · '+current.lapCount+' tours identiques':'')+' · '+current.date+' · '+current.rubric.name:'Aucune séance';
    $('tls-verification').textContent=state.tlsVerificationCode || '';
    $('pairing').textContent=state.pairingCode; $('teacher-code').textContent=state.teacherCode;
    $('wifi-address').textContent = ['localhost','127.0.0.1','[::1]'].includes(location.hostname) ? 'Utilisez l’adresse réseau du PC ou « Rechercher le professeur » sur les appareils. L’adresse 127.0.0.1 désigne le tunnel USB sur les clients.' : 'https://'+location.hostname+':8765';
    $('counts').textContent=state.devices.length+' appareil(s) connu(s) · '+state.sessions.length+' séance(s) conservée(s)';
    $('devices').replaceChildren();
    for(const d of state.devices) {const p=document.createElement('p'); p.textContent=d.name+' · dernière synchronisation : '+d.lastSync.slice(0,19).replace('T',' '); $('devices').append(p);}
    if (!draftLoaded) {
      $('class').replaceChildren(...state.classes.map(c=>option(c.id,c.name)));
      classSelected(); restoreDraft(); describeCourse(); draftLoaded = true;
    }
    for (const id of ['results-session','reception-session']) {
      const old=$(id).value;
      $(id).replaceChildren(...state.sessions.slice().reverse().map(s=>option(s.id,s.schoolClass+' · '+s.distanceMeters+' m · '+s.date+' · '+s.id.slice(0,8))));
      $(id).value=state.sessions.some(s=>s.id===old)?old:state.activeSessionId;
    }
    reception(); results();
  } catch(e) {message('Actualisation impossible. Les données affichées sont conservées. '+e.message);}
  finally { refreshing = false; schedulePoll(); }
}
$('login').onclick=()=>{key=validatedTeacherKey($('key').value);refresh();};
$('refresh').onclick=refresh;
$('class').onchange=()=>{classSelected();saveDraft();describeCourse();};
$('results-session').onchange=results; $('reception-session').onchange=reception;
$('reception-filter').onchange=reception; $('reception-search').oninput=reception;
$('distance').replaceChildren(...Array.from({length:37}, (_,i)=>option(400+i*100,400+i*100)));
$('distance').value='2000';
$('date').value=new Date(Date.now()-new Date().getTimezoneOffset()*60000).toISOString().slice(0,10);
$('session-form').addEventListener('input', () => {describeCourse();saveDraft();});
$('session-form').addEventListener('change', () => {describeCourse();saveDraft();});
$('session-form').onsubmit=event=>{event.preventDefault();saveDraft();selectTab('publication',true);};
$('edit-draft').onclick=()=>selectTab('session',true);
$('publish').onclick=async()=>{
  if (publishing || refreshing) return;
  if (!$('session-form').checkValidity()) { selectTab('session',true); $('session-form').reportValidity(); return; }
  publishing=true; $('publish').disabled=true;
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
    const equal = $('course-mode').value==='laps';
    await api('/admin/session',{schoolClass:$('class-name').value,level:$('level').value,title:$('title').value,date:$('date').value,distanceMeters:Number($('distance').value),lapCount:equal?Number($('lap-count').value):null,passageEveryMeters:equal?400:Number($('passage-meters').value),rubric,pupils});
    message('Nouvelle séance publiée. Synchronisez les appareils avant le départ.');
    publishing=false; await refresh();
  }catch(e){message(e.message);}finally{publishing=false;$('publish').disabled=false;}
};
describeCourse();
if(key) refresh();
}
