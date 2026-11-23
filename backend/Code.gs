/**
 * Stock Tracker API - Google Apps Script (bound to the Google Sheet)
 * Sheets required: Users, Master_Distributor, Master_SKU, Submissions_Log, Stock_Items
 * Deploy: Deploy > New deployment > Web app > Execute as: Me, Who has access: Anyone
 */
const SALT = 'CHANGE_THIS_SECRET_SALT';   // KEEP the value you already use
const TOKEN_TTL = 21600;
const STOCK_HDR = ['SubmissionID','Timestamp','Email','Region','Zone','Base','Distributor','Brand','SKU','Pcs'];
const SALES_HDR = ['SubmissionID','Timestamp','Email','Region','Zone','Base','Distributor','SecondarySalesBDT'];                  // seconds (max 6h for CacheService)

function ss_() { return SpreadsheetApp.getActiveSpreadsheet(); }
function json_(o) { return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON); }
function hash_(pw) {
  return Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, SALT + pw)
    .map(function (b) { return ('0' + (b & 0xff).toString(16)).slice(-2); }).join('');
}
function rows_(name) {
  const sh = ss_().getSheetByName(name);
  if (!sh || sh.getLastRow() < 2) return [];
  const v = sh.getDataRange().getValues(); v.shift(); return v;
}
function sheet_(name, hdr) {
  const s = ss_(); let sh = s.getSheetByName(name) || s.insertSheet(name);
  sh.getRange(1, 1, 1, hdr.length).setValues([hdr]).setFontWeight('bold'); sh.setFrozenRows(1);
  return sh;
}

function doGet(e) {
  // Web viewer: open  <WebAppURL>?page=web
  if (e && e.parameter && e.parameter.page === 'web')
    return HtmlService.createHtmlOutputFromFile('Index').setTitle('Stock Tracker')
      .addMetaTag('viewport', 'width=device-width, initial-scale=1');
  return json_({ ok: true, msg: 'Stock API running' });
}

/* ---------- Web viewer (called by Index.html via google.script.run) ---------- */
function webLogin(email, password) { return login_({ email: email, password: password }); }

function webData(token) {
  const em = CacheService.getScriptCache().get('t_' + token);
  if (!em) return { ok: false, error: 'SESSION_EXPIRED' };
  const tz = Session.getScriptTimeZone();
  function ts(v) { return Utilities.formatDate(new Date(v), tz, 'yyyy-MM-dd HH:mm'); }
  const stock = rows_('Stock_Items').filter(function (r) { return r[0]; }).map(function (r) {
    return { id: String(r[0]), ts: ts(r[1]), by: r[2], region: r[3], zone: r[4], base: r[5], dist: r[6], brand: r[7], sku: r[8], pcs: Number(r[9]) || 0 };
  });
  const sales = rows_('Sales_Log').filter(function (r) { return r[0]; }).map(function (r) {
    return { id: String(r[0]), ts: ts(r[1]), by: r[2], region: r[3], zone: r[4], base: r[5], dist: r[6], amount: Number(r[7]) || 0 };
  });
  return { ok: true, master: master_(), stock: stock, sales: sales };
}

function doPost(e) {
  try {
    const b = JSON.parse(e.postData.contents);
    if (b.action === 'login') return json_(login_(b));
    const email = CacheService.getScriptCache().get('t_' + b.token);
    if (!email) return json_({ ok: false, error: 'SESSION_EXPIRED' });
    switch (b.action) {
      case 'master':       return json_(master_());
      case 'submit_stock': return json_(submitStock_(email, b));
      case 'submit_sales': return json_(submitSales_(email, b));
      case 'dashboard':    return json_(dashboard_());
      case 'webdata':      return json_(webData(b.token));
      default:             return json_({ ok: false, error: 'Unknown action' });
    }
  } catch (err) { return json_({ ok: false, error: String(err) }); }
}

function login_(b) {
  const em = String(b.email || '').trim().toLowerCase();
  const u = rows_('Users').find(function (r) { return String(r[0]).toLowerCase() === em; });
  if (!u || String(u[3]).toUpperCase() === 'FALSE' || u[1] !== hash_(String(b.password || '')))
    return { ok: false, error: 'Invalid email or password' };
  const token = Utilities.getUuid();
  CacheService.getScriptCache().put('t_' + token, em, TOKEN_TTL);
  return { ok: true, token: token, name: u[2] };
}

// regions: Region > Zone > Base > [Distributor];  brands: Brand > [SKU]
function master_() {
  const regions = {}, brands = {};
  rows_('Master_Distributor').forEach(function (r) {
    if (!r[0] || !r[1] || !r[2] || !r[3]) return;
    const z = regions[r[0]] = regions[r[0]] || {};
    const b = z[r[1]] = z[r[1]] || {};
    (b[r[2]] = b[r[2]] || []).push(r[3]);
  });
  rows_('Master_SKU').forEach(function (r) {
    if (!r[0] || !r[1]) return;
    (brands[r[0]] = brands[r[0]] || []).push(r[1]);
  });
  return { ok: true, regions: regions, brands: brands };
}

// body: {region, zone, base, distributor, items:[{brand, sku, pcs}]}
function submitStock_(email, b) {
  if (!b.distributor || !b.items || !b.items.length) return { ok: false, error: 'Distributor and items required' };
  const lock = LockService.getScriptLock(); lock.waitLock(20000);
  try {
    const id = Utilities.getUuid(), now = new Date();
    const rows = b.items.map(function (i) {
      const p = Number(i.pcs);
      if (!(p > 0)) throw new Error('Invalid pcs');
      return [id, now, email, b.region, b.zone, b.base, b.distributor, i.brand, i.sku, p];
    });
    const sh = sheet_('Stock_Items', STOCK_HDR);
    sh.getRange(sh.getLastRow() + 1, 1, rows.length, STOCK_HDR.length).setValues(rows);
    return { ok: true, id: id };
  } finally { lock.releaseLock(); }
}

// body: {region, zone, base, distributor, amount}  (amount = secondary sales in BDT)
function submitSales_(email, b) {
  const amt = Number(b.amount);
  if (!b.distributor || !(amt > 0)) return { ok: false, error: 'Distributor and amount required' };
  const lock = LockService.getScriptLock(); lock.waitLock(20000);
  try {
    const id = Utilities.getUuid();
    sheet_('Sales_Log', SALES_HDR).appendRow([id, new Date(), email, b.region, b.zone, b.base, b.distributor, amt]);
    return { ok: true, id: id };
  } finally { lock.releaseLock(); }
}

// Per distributor: stock (latest value per SKU), secondary sales (sum of BDT), counts, last date.
function dashboard_() {
  const tz = Session.getScriptTimeZone(), agg = {};
  function get(r, z, b, d) {
    const k = [r, z, b, d].join('|');
    return agg[k] = agg[k] || { region: r, zone: z, base: b, distributor: d, stockSubs: 0, salesSubs: 0,
      totalPcs: 0, totalSales: 0, last: '', skus: {}, ids: {} };
  }
  function fmt(t) { return Utilities.formatDate(new Date(t), tz, 'yyyy-MM-dd HH:mm'); }
  rows_('Stock_Items').forEach(function (r) {
    if (!r[0]) return;
    const a = get(r[3], r[4], r[5], r[6]);
    if (!a.ids[r[0]]) { a.ids[r[0]] = 1; a.stockSubs++; }
    const t = fmt(r[1]); if (t > a.last) a.last = t;
    const k = r[7] + '|' + r[8], ts = new Date(r[1]).getTime(), p = Number(r[9]) || 0;
    const x = a.skus[k] = a.skus[k] || { brand: r[7], sku: r[8], stock: 0, ts: 0 };
    if (ts > x.ts) { x.ts = ts; x.stock = p; } else if (ts === x.ts) { x.stock += p; }
  });
  rows_('Sales_Log').forEach(function (r) {
    if (!r[0]) return;
    const a = get(r[3], r[4], r[5], r[6]); a.salesSubs++; a.totalSales += Number(r[7]) || 0;
    const t = fmt(r[1]); if (t > a.last) a.last = t;
  });
  const data = Object.keys(agg).sort().map(function (k) {
    const a = agg[k]; delete a.ids;
    a.skus = Object.keys(a.skus).map(function (s) { return a.skus[s]; })
      .sort(function (x, y) { return String(x.brand).localeCompare(String(y.brand)) || String(x.sku).localeCompare(String(y.sku)); });
    a.skus.forEach(function (x) { a.totalPcs += x.stock; delete x.ts; });
    return a;
  });
  return { ok: true, data: data };
}

/* ---------- Sheet menu: manage users ---------- */
function onOpen() {
  SpreadsheetApp.getUi().createMenu('Stock App').addItem('Add / reset user', 'addUser').addItem('Setup / migrate sheets', 'setupSheets').addToUi();
}
function addUser() {
  const ui = SpreadsheetApp.getUi();
  const e = ui.prompt('User email'); if (e.getSelectedButton() !== ui.Button.OK) return;
  const p = ui.prompt('Password'); if (p.getSelectedButton() !== ui.Button.OK) return;
  const n = ui.prompt('Display name'); if (n.getSelectedButton() !== ui.Button.OK) return;
  const sh = ss_().getSheetByName('Users'), em = e.getResponseText().trim().toLowerCase();
  const data = sh.getDataRange().getValues();
  for (let i = 1; i < data.length; i++) {
    if (String(data[i][0]).toLowerCase() === em) {
      sh.getRange(i + 1, 1, 1, 4).setValues([[em, hash_(p.getResponseText()), n.getResponseText(), true]]);
      ui.alert('User updated'); return;
    }
  }
  sh.appendRow([em, hash_(p.getResponseText()), n.getResponseText(), true]);
  ui.alert('User added');
}

/* One-time: migrate an existing sheet to Region > Zone > Base > Distributor layout */
function setupSheets() {
  const ui = SpreadsheetApp.getUi(), s = ss_();
  if (ui.alert('Setup / migrate', 'This CLEARS old Stock_Items / Submissions_Log data and rebuilds headers. Users and Master sheets are kept. Continue?', ui.ButtonSet.YES_NO) !== ui.Button.YES) return;
  const md = s.getSheetByName('Master_Distributor');
  if (md.getRange('C1').getValue() !== 'Base') {
    md.insertColumnAfter(2);
    md.getRange('C1').setValue('Base');
    const n = md.getLastRow() - 1;
    if (n > 0) md.getRange(2, 3, n, 1).setValue('Base 1');   // placeholder - edit these
  }
  const old = s.getSheetByName('Submissions_Log'); if (old) s.deleteSheet(old);
  ['Stock_Items', 'Sales_Log'].forEach(function (n) {
    const sh = s.getSheetByName(n) || s.insertSheet(n); sh.clear();
  });
  sheet_('Stock_Items', STOCK_HDR); sheet_('Sales_Log', SALES_HDR);
  const d = s.getSheetByName('Dashboard') || s.insertSheet('Dashboard'); d.clear();
  d.getRange('A1').setFormula('=IFERROR(QUERY(Sales_Log!A:H,"select D,E,F,G,sum(H) where G is not null group by D,E,F,G label D \'Region\', E \'Zone\', F \'Base\', G \'Distributor\', sum(H) \'Secondary Sales BDT\'",1),"No sales yet")');
  ui.alert('Done. Now edit the "Base" column in Master_Distributor with your real base names.');
}
