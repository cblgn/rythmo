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
if (location.hash) history.replaceState(null, '', location.pathname + location.search);
let state, refreshing = false, publishing = false, draftLoaded = false, selectedTab = 'session', poll;
const draftName = 'rythmo-session-draft-v1';
const tabNames = ['devices', 'reception', 'results'];
const fields = [...$('session-form').querySelectorAll('input:not([type=file]),select,textarea')];
const {scaledInteger, importTime, tableFromText, pupilsFromText, scoreSummary} = globalThis.RythmoPreparation;
let step = 'class';
function showStep(name) {
  step = name;
  for (const id of ['class', 'course', 'rubric']) $('step-'+id).hidden = name !== id;
  document.querySelectorAll('[data-step]').forEach(button => button.setAttribute('aria-current', button.dataset.step === name ? 'step' : 'false'));
}
document.querySelectorAll('[data-step]').forEach(button => { button.onclick = () => showStep(button.dataset.step); });
function rubricFields() {
  const custom = $('rubric-mode').value === 'assessment';
  for (const [id, visible] of [['assessment-rubric', custom], ['demo-rubric', !custom]]) {
    $(id).hidden = !visible;
    $(id).querySelectorAll('input,select,textarea,button').forEach(field => { field.disabled = !visible; });
  }
  const comparisons = Math.max(0, ($('course-mode').value === 'laps' ? Number($('lap-count').value) : Math.floor(Number($('distance').value)/Number($('passage-meters').value))) - 1);
  const threshold = $('comparison-threshold').value;
  const max = Number($('perf-max').value) + Number($('comparison-max').value);
  $('rubric-summary').textContent = `${comparisons} comparaisons. Écart (tour actuel − précédent) ≤ ${threshold} s : comparaison réussie. Points = réussites / ${comparisons || '—'} × ${$('comparison-max').value}. Total sur ${max} ; conversion sur 20.`;
}
function formValid() {
  rubricFields();
  const invalid = fields.find(field => !field.disabled && !field.checkValidity());
  if (!invalid) return true;
  selectTab('session');
  const panel = invalid.closest('[id^="step-"]');
  if (panel) showStep(panel.id.substring(5));
  invalid.reportValidity();
  return false;
}
function buildSession() {
  const prior = state.classes.find(c => c.id === $('class').value);
  const pupils = pupilsFromText($('pupils').value, prior?.pupils);
  const equal = $('course-mode').value === 'laps';
  const session = {schoolClass: $('class-name').value.trim(), level: $('level').value, title: $('title').value.trim(), date: $('date').value,
    distanceMeters: Number($('distance').value), lapCount: equal ? Number($('lap-count').value) : null,
    passageEveryMeters: equal ? 400 : Number($('passage-meters').value), pupils};
  if ($('rubric-mode').value === 'assessment') {
    session.assessment = {name: $('assessment-name').value, performanceMaxTenths: scaledInteger($('perf-max').value, 10),
      comparisonMaxTenths: scaledInteger($('comparison-max').value, 10), comparisonThresholdMs: scaledInteger($('comparison-threshold').value, 1000),
      tables: {BOY: tableFromText($('table-boys').value), GIRL: tableFromText($('table-girls').value)}};
  } else session.rubric = {name: $('rubric-name').value, maxGradeTenths: scaledInteger($('grade-max').value, 10),
    reference2000Ms: scaledInteger($('reference').value, 1000), boyPermille: scaledInteger($('boys').value, 1000), girlPermille: scaledInteger($('girls').value, 1000),
    levelsPermille: Object.fromEntries(['6e','5e','4e','3e'].map(l => [l, scaledInteger($('coef'+l).value, 1000)]))};
  return session;
}
function renderRows(target, rows) {
  const table = document.createElement('table');
  rows.slice(0, 35).forEach((values, index) => {
    const row = document.createElement('tr');
    appendCells(row, [index + 1, ...values]); table.append(row);
  });
  target.replaceChildren(table);
}
async function readTableFile(file) {
  if (!file || file.size > 2000000) throw Error('Choisissez un fichier CSV ou XLSX de moins de 2 Mo.');
  const bytes = new Uint8Array(await file.arrayBuffer());
  let binary = '';
  for (let offset = 0; offset < bytes.length; offset += 8192) binary += String.fromCharCode(...bytes.subarray(offset, offset+8192));
  return (await api('/admin/import/preview', {filename: file.name, base64: btoa(binary)})).json();
}
function tableImport(kind, maps, apply) {
  let workbook;
  const prefix = kind === 'class' ? 'class' : 'rubric';
  function sheet() { return workbook?.sheets[Number($(prefix+'-sheet').value)]; }
  function populate() {
    const current = sheet(); if (!current) return;
    const count = Math.max(0, ...current.rows.map(row => row.length));
    maps.forEach((id, i) => {
      $(id).replaceChildren(...Array.from({length: count}, (_, col) => option(col, `${col+1} · ${current.rows[0]?.[col] || 'Colonne'}`)));
      $(id).value = String(Math.min(i, count-1));
    });
    renderRows($(prefix+'-preview'), current.rows);
    if (current.ignoredFormulas) message(`${current.ignoredFormulas} cellule(s) de formule ignorée(s). Importez uniquement les valeurs utiles.`);
  }
  $(prefix+'-file').onchange = async event => {
    $(prefix+'-mapping').hidden = true;
    try {
      workbook = await readTableFile(event.target.files[0]);
      $(prefix+'-sheet').replaceChildren(...workbook.sheets.map((s, i) => option(i, s.name)));
      $(prefix+'-mapping').hidden = false; populate();
    } catch (error) { message(error.message); }
    finally { event.target.value = ''; }
  };
  $(prefix+'-sheet').onchange = populate;
  $('apply-'+prefix).onclick = () => {
    try {
      const start = Number($(prefix+'-first-row').value);
      const current = sheet();
      if (!Number.isInteger(start) || start < 1 || start > current.rows.length) throw Error('Première ligne invalide.');
      const columns = maps.map(id => Number($(id).value));
      if (new Set(columns).size !== columns.length) throw Error('Choisissez des colonnes différentes.');
      apply(current.rows.slice(start-1).map(row => columns.map(col => (row[col] || '').trim())));
      saveDraft(); message('Aperçu appliqué au brouillon. Vérifiez les valeurs avant enregistrement.');
    } catch (error) { message(error.message); }
  };
}
tableImport('class', ['map-first', 'map-last', 'map-sex'], rows => {
  const text = rows.map(([first, last, sex]) => {
    const normalized = sex.toLocaleLowerCase('fr');
    const profile = ['f','fille','girl','féminin'].includes(normalized) ? 'F' : ['g','m','garçon','boy','masculin'].includes(normalized) ? 'G' : sex;
    return [first,last,profile].join(';');
  }).join('\n');
  pupilsFromText(text);
  $('pupils').value = text;
});
tableImport('rubric', ['map-time', 'map-points'], rows => {
  const text = rows.map(([time, points]) => `${duration(importTime(time, $('time-unit').value))};${points}`).join('\n');
  tableFromText(text);
  $($('rubric-profile').value).value = text;
});
$('new-class').onclick = () => { $('class').value = ''; $('class-name').value = ''; $('pupils').value = ''; saveDraft(); $('class-name').focus(); };
$('save-class').onclick = async () => {
  if (publishing || refreshing) return;
  try {
    const incoming = {id: $('class').value || 'new', name: $('class-name').value.trim(), level: $('level').value,
      pupils: pupilsFromText($('pupils').value, state.classes.find(c => c.id === $('class').value)?.pupils)};
    const cls = await (await api('/admin/classes', incoming)).json();
    state.classes = state.classes.filter(c => c.id !== cls.id).concat(cls);
    $('class').replaceChildren(option('', 'Nouvelle classe'), ...state.classes.map(c => option(c.id, c.name)));
    $('class').value = cls.id; classSelected(); saveDraft(); message('Classe enregistrée.');
  } catch (error) { message(error.message); }
};
$('fictional-rubric').onclick = () => {
  $('assessment-name').value = 'Exemple fictif — performance et progression';
  $('perf-max').value = '10'; $('comparison-max').value = '5'; $('comparison-threshold').value = '0';
  $('table-boys').value = '5:00;10\n6:00;5\n7:00;0';
  $('table-girls').value = '5:30;10\n6:30;5\n7:30;0';
  rubricFields(); saveDraft();
};
function downloadConfig(value) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], {type:'application/json'}));
  const a = document.createElement('a'); a.href = url; a.download = 'rythmo-preparation.json'; a.click(); setTimeout(() => URL.revokeObjectURL(url), 30000);
}
$('export-preparation').onclick = async () => {
  try {
    if (!formValid()) return;
    const checked = await (await api('/admin/preparation/validate', {schemaVersion:1, session:buildSession()})).json();
    downloadConfig(checked);
  } catch (error) { message(error.message); }
};
$('import-preparation').onchange = async event => {
  try {
    const file = event.target.files[0];
    if (!file || file.size > 2000000) throw Error('Configuration trop volumineuse.');
    const input = JSON.parse(await file.text());
    const imported = await (await api('/admin/preparation/validate', input)).json();
    const s = imported.session;
    const existingClass = state.classes.find(c => c.name === s.schoolClass && c.level === s.level);
    const values = {'class':existingClass?.id || '','class-name':s.schoolClass,level:s.level,title:s.title,date:s.date,distance:s.distanceMeters,
      'course-mode':s.lapCount ? 'laps':'distance','lap-count':s.lapCount || 6,'passage-meters':s.passageEveryMeters,
      pupils:s.pupils.map(p => [p.firstName,p.lastName,p.sex === 'GIRL'?'F':'G'].join(';')).join('\n'), 'rubric-mode':s.assessment?'assessment':'demo'};
    if (s.assessment) Object.assign(values, {'assessment-name':s.assessment.name,'perf-max':s.assessment.performanceMaxTenths/10,
      'comparison-max':s.assessment.comparisonMaxTenths/10,'comparison-threshold':s.assessment.comparisonThresholdMs/1000,
      'table-boys':s.assessment.tables.BOY.map(r => `${duration(r.timeMs)};${r.pointsTenths/10}`).join('\n'),
      'table-girls':s.assessment.tables.GIRL.map(r => `${duration(r.timeMs)};${r.pointsTenths/10}`).join('\n')});
    else Object.assign(values, {'rubric-name':s.rubric.name,'grade-max':s.rubric.maxGradeTenths/10,'reference':s.rubric.reference2000Ms/1000,
      boys:s.rubric.boyPermille/1000,girls:s.rubric.girlPermille/1000,...Object.fromEntries(Object.entries(s.rubric.levelsPermille).map(([l,v])=>['coef'+l,v/1000]))});
    Object.entries(values).forEach(([id,value]) => { $(id).value = String(value); });
    describeCourse(); rubricFields(); saveDraft(); selectTab('session'); showStep('class');
    message('Configuration chargée dans le brouillon. La séance publiée reste inchangée.');
  } catch(error) { message(error.message); }
  finally { event.target.value = ''; }
};

function message(text) {
  const label = document.createElement('span'); label.textContent = text;
  const close = document.createElement('button'); close.className = 'secondary'; close.textContent = 'Fermer';
  close.setAttribute('aria-label', 'Fermer le message'); close.onclick = () => { $('message').style.display = 'none'; };
  $('message').replaceChildren(label, close); $('message').style.display = 'flex'; $('message').style.gap = '12px';
}
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
  const custom = $('rubric-mode').value === 'assessment';
  const lines = [$('title').value, `${$('class-name').value} · ${$('level').value} · ${$('date').value}`,
    $('course-summary').textContent, `${$('pupils').value.split('\n').filter(l => l.trim()).length} élèves`,
    custom ? $('assessment-name').value : $('rubric-name').value,
    custom ? $('rubric-summary').textContent : `Note sur ${$('grade-max').value} · référence 2000 m ${$('reference').value} s`];
  lines.forEach(text => { const p = document.createElement('p'); p.textContent = text; $('publication-summary').append(p); });
  if (custom) for (const id of ['table-boys','table-girls']) {
    const title = document.createElement('h3'); title.textContent = id === 'table-boys' ? 'Performance garçons' : 'Performance filles';
    const container = document.createElement('div'); container.className = 'table';
    renderRows(container, [['Temps à partir de', 'Points'], ...$(id).value.split('\n').filter(l => l.trim()).map(l => l.split(';'))]);
    $('publication-summary').append(title, container);
  }
}
function schedulePoll() {
  clearTimeout(poll);
  if (!document.hidden && ['reception', 'results'].includes(selectedTab) && state) poll = setTimeout(async () => { await refresh(); schedulePoll(); }, 5000);
}
function selectTab(name, focus = false) {
  if (!tabNames.includes(name)) name = 'devices';
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
    appendCells(row, [r.pupil.firstName+' '+r.pupil.lastName,claim?.deviceName||result.upload.deviceId,r.abandoned?'Abandon · '+duration(total(r))+' enregistré':duration(total(r)),result.assessmentScore ? scoreSummary(result.assessmentScore) : result.gradeTenths==null?'Non noté':(result.gradeTenths/10).toLocaleString('fr-FR')+' / '+((session.rubric.maxGradeTenths??200)/10).toLocaleString('fr-FR'),[corrections,cancellations].filter(Boolean).join(' ; ')]);
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
    $('active').textContent=current?current.schoolClass+' · '+current.distanceMeters+' m'+(current.lapCount?' · '+current.lapCount+' tours identiques':'')+' · '+current.date+' · '+(current.assessment?.name || current.rubric.name):'Aucune séance';
    $('tls-verification').textContent=state.tlsVerificationCode || '';
    $('pairing').textContent=state.pairingCode; $('teacher-code').textContent=state.teacherCode;
    $('wifi-address').textContent = ['localhost','127.0.0.1','[::1]'].includes(location.hostname) ? 'Utilisez l’adresse réseau du PC ou « Rechercher le professeur » sur les appareils. L’adresse 127.0.0.1 désigne le tunnel USB sur les clients.' : 'https://'+location.hostname+':8765';
    $('counts').textContent=state.devices.length+' appareil(s) connu(s) · '+state.sessions.length+' séance(s) conservée(s)';
    $('devices').replaceChildren();
    for(const d of state.devices) {const p=document.createElement('p'); p.textContent=d.name+' · dernière synchronisation : '+d.lastSync.slice(0,19).replace('T',' '); $('devices').append(p);}
    if (!draftLoaded) {
      $('class').replaceChildren(option('', 'Nouvelle classe'), ...state.classes.map(c=>option(c.id,c.name)));
      $('class').value = state.classes[0]?.id || '';
      classSelected(); restoreDraft(); describeCourse(); rubricFields(); draftLoaded = true;
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
$('distance').value='1000';
$('date').value=new Date(Date.now()-new Date().getTimezoneOffset()*60000).toISOString().slice(0,10);
$('session-form').addEventListener('input', () => {describeCourse();rubricFields();saveDraft();});
$('session-form').addEventListener('change', () => {describeCourse();rubricFields();saveDraft();});
$('session-form').onsubmit=async event=>{event.preventDefault(); try { if (!formValid()) return; await api('/admin/preparation/validate',{schemaVersion:1,session:buildSession()}); saveDraft();selectTab('publication',true); } catch(error) {message(error.message);} };
$('edit-draft').onclick=()=>selectTab('session',true);
$('publish').onclick=async()=>{
  if (publishing || refreshing) return;
  if (!formValid()) return;
  publishing=true; $('publish').disabled=true;
  try {
    await api('/admin/session', buildSession());
    message('Nouvelle séance publiée. Synchronisez les appareils avant le départ.');
    publishing=false; await refresh();
  }catch(e){message(e.message);}finally{publishing=false;$('publish').disabled=false;}
};
describeCourse(); rubricFields();
const initialTab = new URLSearchParams(location.search).get('tab');
selectTab(tabNames.includes(initialTab) ? initialTab : 'devices');
if(key) refresh();
}
