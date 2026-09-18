const {test} = require('node:test');
const assert = require('node:assert/strict');
const {courseSummary, receptionRows, receptionCounts, readDraft} = require('../core/src/main/resources/teacher/admin.js');

test('course descriptions preserve the short last segment and exact equal laps', () => {
  assert.equal(courseSummary(2000,'distance',400,6), '2000 m = 5 × 400 m');
  assert.equal(courseSummary(1000,'distance',400,6), '1000 m = 2 × 400 m + 200 m');
  assert.equal(courseSummary(1000,'laps',400,6), '1000 m en 6 tours identiques');
});
test('only assigned pupils have expected reports; abandonment needs no PDF', () => {
  const session = {id:'current', pupils:[1,2,3,4].map(id=>({id}))};
  const report = (id, abandoned) => ({upload:{sessionId:'current', runner:{pupil:{id}, abandoned}, pdfBase64:null}});
  const state = {claims:[{sessionId:'current',pupilIds:[2,3,4],deviceName:'A'}, {sessionId:'old',pupilIds:[1],deviceName:'B'}], results:[report(3,false),report(4,true)]};
  assert.deepEqual(receptionRows(state, session).map(r=>r.status), ['Non affecté','Bilan attendu','Reçu','Abandon']);
  assert.deepEqual(receptionCounts(receptionRows(state, session)), {'Non affecté':1,'Bilan attendu':1,'Reçu':1,'Abandon':1});
  assert.equal(receptionRows(state, session)[0].device, '—');
});
test('draft values survive browser storage and corrupted storage is handled', () => {
  const draft = {'class-name':'6e Exemple', 'distance':'1000', 'course-mode':'laps','lap-count':'6','pupils':'Alice;Exemple;F'};
  assert.deepEqual(readDraft({getItem:()=>JSON.stringify(draft)},'draft'), draft);
  assert.deepEqual(readDraft({getItem:()=>'{broken'},'draft'), {});
  assert.deepEqual(readDraft({getItem:()=>null},'draft'), {});
});

test('teacher key validation rejects poisoned browser storage and malformed URL fragments', () => {
  const {validatedTeacherKey} = require('../core/src/main/resources/teacher/admin.js');
  for (const value of [null, {}, '', 'javascript:alert(1)', '<script>', '00000000-0000-0000-0000-000000000000\nHeader: evil']) assert.equal(validatedTeacherKey(value), '');
  assert.equal(validatedTeacherKey(' C102D24B-FADE-4123-8345-9BE66A647111 '), 'c102d24b-fade-4123-8345-9be66a647111');
});
