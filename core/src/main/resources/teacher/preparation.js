'use strict';
function scaledInteger(text, scale) {
  const value = String(text).trim().replace(',', '.');
  if (!/^-?\d+(\.\d{1,12})?$/.test(value)) throw Error('Nombre invalide : '+text);
  const negative = value.startsWith('-'), parts = value.replace('-', '').split('.');
  const denominator = 10n ** BigInt((parts[1] || '').length);
  const numerator = BigInt(parts.join('')) * BigInt(scale);
  const result = Number((numerator + denominator / 2n) / denominator) * (negative ? -1 : 1);
  if (!Number.isSafeInteger(result)) throw Error('Nombre trop grand.');
  return result;
}
function importTime(text, unit = 'clock') {
  let time;
  if (unit === 'excel') time = scaledInteger(text, 86400000);
  else if (unit === 'seconds' || !String(text).includes(':')) time = scaledInteger(text, 1000);
  else {
    const match = /^(\d{1,4}):([0-5]\d)(?:[.,](\d{1,3}))?$/.exec(String(text).trim());
    if (!match) throw Error('Temps attendu : minutes:secondes, par exemple 5:30.');
    time = Number(match[1]) * 60000 + Number(match[2]) * 1000 + Number((match[3] || '').padEnd(3, '0'));
  }
  if (time <= 0 || time > 86400000) throw Error('Le temps doit être positif et inférieur ou égal à 24 heures.');
  return time;
}
function tableFromText(text) {
  const rows = text.split('\n').filter(l => l.trim()).map(line => {
    const parts = line.split(';');
    if (parts.length !== 2) throw Error('Table attendue : temps;points, une ligne par palier.');
    return {timeMs: importTime(parts[0]), pointsTenths: scaledInteger(parts[1], 10)};
  });
  if (!rows.length || rows.length > 1000) throw Error('La table doit contenir de 1 à 1000 paliers.');
  rows.forEach((row, i) => {
    if (row.pointsTenths < 0 || (i && (row.timeMs <= rows[i-1].timeMs || row.pointsTenths > rows[i-1].pointsTenths)))
      throw Error('Les temps doivent augmenter et les points ne doivent pas augmenter.');
  });
  return rows;
}
function pupilsFromText(text, previous = []) {
  const names = new Set();
  const normalized = value => value.trim().normalize('NFKC').toLocaleLowerCase('fr');
  const pupils = text.split('\n').filter(l => l.trim()).map(line => {
    const [firstName, lastName, profile, ...extra] = line.split(';').map(v => v.trim());
    if (!firstName || !lastName || extra.length || !['F', 'G'].includes(profile)) throw Error('Format attendu : Prénom;Nom;F ou G');
    const key = normalized(firstName)+'\0'+normalized(lastName);
    if (names.has(key)) throw Error('Doublon : '+firstName+' '+lastName+'. Distinguez ces élèves avant publication.');
    names.add(key);
    const prior = previous.filter(p => normalized(p.firstName) === normalized(firstName) && normalized(p.lastName) === normalized(lastName));
    if (prior.length > 1) throw Error('Identité existante ambiguë : '+firstName+' '+lastName);
    return {...(prior.length ? {id: prior[0].id} : {}), firstName, lastName, sex: profile === 'F' ? 'GIRL' : 'BOY'};
  });
  if (!pupils.length || pupils.length > 30) throw Error('Une classe doit contenir de 1 à 30 élèves.');
  return pupils;
}
function scoreSummary(score) {
  const points = n => (n / 10).toLocaleString('fr-FR');
  return `${points(score.totalTenths)} / ${points(score.maxTenths)} · ${points(score.outOf20Tenths)} / 20 (performance ${points(score.performanceTenths)}, comparaison ${points(score.comparisonTenths)})`;
}
const preparationFunctions = {scaledInteger, importTime, tableFromText, pupilsFromText, scoreSummary};
if (typeof module !== 'undefined') module.exports = preparationFunctions;
else globalThis.RythmoPreparation = preparationFunctions;
