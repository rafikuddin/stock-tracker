/**
 * Stock Tracker API - Google Apps Script (bound to the Google Sheet)
 * Master data comes straight from your two dump files, imported as tabs of this Sheet:
 *   CUSTOMER_MASTER (tabs Adhesive / Corporate / Thinner)  -> Region > Zone > Base > Distributor
 *   PRODUCT_MASTER  (tab SKU_MASTER)                        -> Brand > SKU
 * Tabs are found by their column headers, so tab names do not matter.
 * Other tabs: Users (Email, PasswordHash, Name, Active, Role), Stock_Items, Sales_Log (created automatically).
 * Role = admin  ->  may add and edit submitted data on the web page. Everyone else can only view and download.
 * Deploy: Deploy > New deployment > Web app > Execute as: Me, Who has access: Anyone
 */
const SALT = 'CHANGE_THIS_SECRET_SALT';   // KEEP the value you already use
const TOKEN_TTL = 21600;
// (TOKEN_TTL above is in seconds; CacheService allows max 6h)
// EditedBy / EditedAt are filled only when an admin changes a row on the web page.
const STOCK_HDR = ['SubmissionID','Timestamp','Email','Region','Zone','Base','Distributor','Brand','SKU','Pcs','DistributorCode','BU','SKUCode','EditedBy','EditedAt'];
const SALES_HDR = ['SubmissionID','Timestamp','Email','Region','Zone','Base','Distributor','SecondarySalesBDT','DistributorCode','BU','EditedBy','EditedAt'];
// Users tab: A Email | B PasswordHash | C Name | D Active | E Role  ("admin" = may add/edit data on the web page)

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

/* ---------- Roles ---------- */
function userRow_(email) {
  const em = String(email || '').trim().toLowerCase();
  return rows_('Users').find(function (r) { return String(r[0]).toLowerCase() === em; });
}
function roleOf_(u) { return u && String(u[4] || '').trim().toLowerCase() === 'admin' ? 'admin' : 'user'; }
function isAdmin_(email) {
  const u = userRow_(email);
  return !!u && String(u[3]).toUpperCase() !== 'FALSE' && roleOf_(u) === 'admin';
}
function msg_(err) { return String(err && err.message ? err.message : err); }

/* ---------- Web viewer (called by Index.html via google.script.run) ---------- */
function webLogin(email, password) { return login_({ email: email, password: password }); }

// Same rows as rows_(), but each keeps its sheet row number (used by admin edits).
function rowsX_(name) {
  const sh = ss_().getSheetByName(name);
  if (!sh || sh.getLastRow() < 2) return [];
  return sh.getRange(2, 1, sh.getLastRow() - 1, sh.getLastColumn()).getValues()
    .map(function (v, i) { return { row: i + 2, v: v }; });
}

function webData(token) {
  const em = CacheService.getScriptCache().get('t_' + token);
  if (!em) return { ok: false, error: 'SESSION_EXPIRED' };
  const tz = Session.getScriptTimeZone();
  function ts(v) { return Utilities.formatDate(new Date(v), tz, 'yyyy-MM-dd HH:mm'); }
  const stock = rowsX_('Stock_Items').filter(function (x) { return x.v[0]; }).map(function (x) {
    const r = x.v;
    return { row: x.row, id: String(r[0]), ts: ts(r[1]), by: r[2], region: r[3], zone: r[4], base: r[5], dist: r[6], brand: r[7], sku: r[8], pcs: Number(r[9]) || 0,
      dcode: r[10] || '', bu: r[11] || '', skucode: r[12] || '', eb: r[13] || '', ea: r[14] ? ts(r[14]) : '' };
  });
  const sales = rowsX_('Sales_Log').filter(function (x) { return x.v[0]; }).map(function (x) {
    const r = x.v;
    return { row: x.row, id: String(r[0]), ts: ts(r[1]), by: r[2], region: r[3], zone: r[4], base: r[5], dist: r[6], amount: Number(r[7]) || 0,
      dcode: r[8] || '', bu: r[9] || '', eb: r[10] || '', ea: r[11] ? ts(r[11]) : '' };
  });
  const u = userRow_(em);
  return { ok: true, master: master_(), stock: stock, sales: sales, admin: isAdmin_(em), me: { email: em, name: u ? u[2] : em } };
}

// Admin-only writes from the web page. b = {op, ...}
function webAdmin(token, b) {
  const em = CacheService.getScriptCache().get('t_' + token);
  if (!em) return { ok: false, error: 'SESSION_EXPIRED' };
  try { return admin_(em, b || {}); } catch (err) { return { ok: false, error: msg_(err) }; }
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
      case 'admin':        return json_(admin_(email, b));
      default:             return json_({ ok: false, error: 'Unknown action' });
    }
  } catch (err) { return json_({ ok: false, error: msg_(err) }); }
}

function login_(b) {
  const em = String(b.email || '').trim().toLowerCase();
  const u = userRow_(em);
  if (!u || String(u[3]).toUpperCase() === 'FALSE' || u[1] !== hash_(String(b.password || '')))
    return { ok: false, error: 'Invalid email or password' };
  const token = Utilities.getUuid();
  CacheService.getScriptCache().put('t_' + token, em, TOKEN_TTL);
  return { ok: true, token: token, name: u[2], role: roleOf_(u) };
}

/* ---------- Master data (read from the two dump tabs) ---------- */
// Find tabs whose row-1 headers contain all of `need`; returns [{name, idx:{HEADER:col}, rows}]
function tabsByHeaders_(need) {
  const out = [];
  ss_().getSheets().forEach(function (sh) {
    if (sh.getLastRow() < 2 || sh.getLastColumn() < 2) return;
    const head = sh.getRange(1, 1, 1, sh.getLastColumn()).getValues()[0].map(function (h) { return String(h).trim().toUpperCase(); });
    const idx = {}; head.forEach(function (h, i) { if (h && !(h in idx)) idx[h] = i; });
    if (need.every(function (n) { return n in idx; }))
      out.push({ name: sh.getName(), idx: idx, rows: sh.getRange(2, 1, sh.getLastRow() - 1, head.length).getValues() });
  });
  return out;
}
function sortedObj_(o, valFn) {
  const r = {}; Object.keys(o).sort().forEach(function (k) { r[k] = valFn ? valFn(o[k]) : o[k]; }); return r;
}

function loadMaster_() {
  const st = { custTabs: [], skuTabs: [], dist: 0, distSkipped: 0, sku: 0, skuSkipped: 0 };
  const regions = {}, distInfo = {}, brands = {}, buBrands = {}, skuInfo = {};

  tabsByHeaders_(['REGION_BASE', 'ASM_BASE', 'BASE_NAME', 'DISTRIBUTOR_CODE', 'DISTRIBUTOR_NAME']).forEach(function (t) {
    st.custTabs.push(t.name);
    t.rows.forEach(function (r) {
      const g = function (h) { return t.idx[h] === undefined ? '' : String(r[t.idx[h]]).trim(); };
      const status = g('STATUS').toLowerCase();
      const reg = g('REGION_BASE'), zone = g('ASM_BASE'), base = g('BASE_NAME'), name = g('DISTRIBUTOR_NAME');
      const key = [reg, zone, base, name].join('|');
      if ((status && status !== 'not deprecated') || !reg || !zone || !base || !name || distInfo[key]) { st.distSkipped++; return; }
      distInfo[key] = { code: g('DISTRIBUTOR_CODE'), bu: g('BU') };
      const z = regions[reg] = regions[reg] || {};
      const b = z[zone] = z[zone] || {};
      (b[base] = b[base] || []).push(name);
      st.dist++;
    });
  });

  tabsByHeaders_(['SKU_GROUP', 'ERP_SKU_CODE', 'ERP_SKU_NAME']).forEach(function (t) {
    st.skuTabs.push(t.name);
    t.rows.forEach(function (r) {
      const g = function (h) { return t.idx[h] === undefined ? '' : String(r[t.idx[h]]).trim(); };
      const status = g('SKU_STATUS').toLowerCase();
      const brand = g('SKU_GROUP'), bu = g('BU_CATEGORY');
      const name = g('ERP_SKU_NAME').replace(/^\[[^\]]*\]\s*/, '');   // drop "[43-001] " prefix
      const key = brand + '|' + name;
      if ((status && status !== 'active') || !brand || !name || skuInfo[key]) { st.skuSkipped++; return; }
      skuInfo[key] = { code: g('ERP_SKU_CODE'), bu: bu };
      (brands[brand] = brands[brand] || []).push(name);
      if (bu) { const m = buBrands[bu] = buBrands[bu] || {}; (m[brand] = m[brand] || []).push(name); }
      st.sku++;
    });
  });

  return {
    stats: st, distInfo: distInfo, skuInfo: skuInfo,
    regions: sortedObj_(regions, function (z) { return sortedObj_(z, function (b) { return sortedObj_(b, function (l) { return l.sort(); }); }); }),
    brands: sortedObj_(brands, function (l) { return l.sort(); }),
    buBrands: sortedObj_(buBrands, function (m) { return sortedObj_(m, function (l) { return l.sort(); }); })
  };
}

// regions: Region > Zone > Base > [Distributor];  brands: Brand > [SKU];
// distBu: "region|zone|base|distributor" -> BU;  buBrands: BU > Brand > [SKU]
function master_() {
  const M = loadMaster_(), distBu = {};
  Object.keys(M.distInfo).forEach(function (k) { if (M.distInfo[k].bu) distBu[k] = M.distInfo[k].bu; });
  return { ok: true, regions: M.regions, brands: M.brands, distBu: distBu, buBrands: M.buBrands };
}

function locKey_(b) { return [b.region, b.zone, b.base, b.distributor].join('|'); }
function buClash_(M, bu, si, sku) {
  if (bu && M.buBrands[bu] && si && si.bu && si.bu !== bu)
    throw new Error('"' + sku + '" is a ' + si.bu + ' SKU, but this distributor is ' + bu + '. Remove it and try again.');
}

// Validates and builds the Stock_Items rows of one submission. Does NOT take the lock - callers do.
// strict (admin): the distributor must exist in the master and pcs may be 0.
function buildStockRows_(M, email, b, when, strict) {
  const di = M.distInfo[locKey_(b)];
  if (strict && !di) throw new Error('Pick Region, Zone, Base and Distributor from the lists.');
  const d = di || { code: '', bu: '' }, id = Utilities.getUuid();
  const rows = b.items.map(function (i) {
    const p = Number(i.pcs);
    if (!(strict ? p >= 0 && Math.floor(p) === p : p > 0)) throw new Error('Invalid pcs');
    const si = M.skuInfo[i.brand + '|' + i.sku];
    if (strict && !si) throw new Error('Pick Brand and SKU from the lists.');
    buClash_(M, d.bu, si, i.sku);
    return [id, when, email, b.region, b.zone, b.base, b.distributor, i.brand, i.sku, p, d.code, d.bu, si ? si.code : ''];
  });
  return { id: id, rows: rows };
}
function appendRows_(name, hdr, rows) {
  const sh = sheet_(name, hdr);
  const out = rows.map(function (r) { const x = r.slice(); while (x.length < hdr.length) x.push(''); return x; });
  sh.getRange(sh.getLastRow() + 1, 1, out.length, hdr.length).setValues(out);
}

// body: {region, zone, base, distributor, items:[{brand, sku, pcs}]}
function submitStock_(email, b) {
  if (!b.distributor || !b.items || !b.items.length) return { ok: false, error: 'Distributor and items required' };
  const M = loadMaster_();
  const lock = LockService.getScriptLock(); lock.waitLock(20000);
  try {
    const r = buildStockRows_(M, email, b, new Date(), false);
    appendRows_('Stock_Items', STOCK_HDR, r.rows);
    return { ok: true, id: r.id };
  } finally { lock.releaseLock(); }
}

// body: {region, zone, base, distributor, amount}  (amount = secondary sales in BDT)
function submitSales_(email, b) {
  const amt = Number(b.amount);
  if (!b.distributor || !(amt > 0)) return { ok: false, error: 'Distributor and amount required' };
  const di = loadMaster_().distInfo[locKey_(b)] || { code: '', bu: '' };
  const lock = LockService.getScriptLock(); lock.waitLock(20000);
  try {
    const id = Utilities.getUuid();
    appendRows_('Sales_Log', SALES_HDR, [[id, new Date(), email, b.region, b.zone, b.base, b.distributor, amt, di.code, di.bu]]);
    return { ok: true, id: id };
  } finally { lock.releaseLock(); }
}

/* ---------- Admin: add / edit submitted data (web page only) ---------- */
function dayStr_(d) { return Utilities.formatDate(new Date(d), Session.getScriptTimeZone(), 'yyyy-MM-dd'); }
// dateStr 'yyyy-MM-dd' (blank = keep `base`, or now). Keeps the clock time of `base`. Future dates are refused.
function whenFrom_(dateStr, base) {
  const bs = base ? new Date(base) : new Date();
  if (!dateStr) return bs;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(String(dateStr))) throw new Error('Date must look like 2026-11-23');
  if (dateStr > dayStr_(new Date())) throw new Error('Date cannot be in the future');
  if (dayStr_(bs) === dateStr) return bs;
  const tz = Session.getScriptTimeZone();
  return Utilities.parseDate(dateStr + ' ' + Utilities.formatDate(bs, tz, 'HH:mm:ss'), tz, 'yyyy-MM-dd HH:mm:ss');
}

function admin_(email, b) {
  if (!isAdmin_(email)) return { ok: false, error: 'Only an admin can add or edit data on the web page.' };
  const M = loadMaster_();
  const lock = LockService.getScriptLock(); lock.waitLock(20000);
  try {
    switch (b.op) {
      case 'add_stock':  return adminAddStock_(email, M, b);
      case 'edit_stock': return adminEditStock_(email, M, b);
      case 'add_sales':  return adminAddSales_(email, M, b);
      case 'edit_sales': return adminEditSales_(email, M, b);
      default:           return { ok: false, error: 'Unknown admin action' };
    }
  } finally { lock.releaseLock(); }
}

function adminAddStock_(email, M, b) {
  if (!b.items || !b.items.length) return { ok: false, error: 'Add at least one SKU' };
  const r = buildStockRows_(M, email, b, whenFrom_(b.date, null), true);
  appendRows_('Stock_Items', STOCK_HDR, r.rows);
  return { ok: true, id: r.id, added: r.rows.length };
}

function adminAddSales_(email, M, b) {
  const amt = Number(b.amount), di = M.distInfo[locKey_(b)];
  if (!di) return { ok: false, error: 'Pick Region, Zone, Base and Distributor from the lists.' };
  if (!(amt >= 0)) return { ok: false, error: 'Enter the sales amount in BDT' };
  const id = Utilities.getUuid();
  appendRows_('Sales_Log', SALES_HDR, [[id, whenFrom_(b.date, null), email, b.region, b.zone, b.base, b.distributor, amt, di.code, di.bu]]);
  return { ok: true, id: id, added: 1 };
}

// The row is found by its sheet row number; the SubmissionID must still match, so a row that moved is never overwritten.
function rowForEdit_(name, hdr, b) {
  const sh = sheet_(name, hdr), row = Number(b.row);
  if (!(row >= 2) || row > sh.getLastRow()) throw new Error('Row not found. Refresh the page and try again.');
  const cur = sh.getRange(row, 1, 1, hdr.length).getValues()[0];
  if (String(cur[0]) !== String(b.id)) throw new Error('This row changed in the Sheet. Refresh the page and try again.');
  return { sh: sh, row: row, cur: cur };
}

function adminEditStock_(email, M, b) {
  const x = rowForEdit_('Stock_Items', STOCK_HDR, b), cur = x.cur;
  const p = Number(b.pcs);
  if (!(p >= 0) || Math.floor(p) !== p) return { ok: false, error: 'Pcs must be a whole number' };
  const same = b.region === cur[3] && b.zone === cur[4] && b.base === cur[5] && b.distributor === cur[6];
  const di = M.distInfo[locKey_(b)];
  if (!di && !same) return { ok: false, error: 'Pick Region, Zone, Base and Distributor from the lists.' };
  const sameSku = b.brand === cur[7] && b.sku === cur[8];
  const si = M.skuInfo[b.brand + '|' + b.sku];
  if (!si && !sameSku) return { ok: false, error: 'Pick Brand and SKU from the lists.' };
  const dcode = di ? di.code : cur[10], bu = di ? di.bu : cur[11];
  if (si) buClash_(M, bu, si, b.sku);
  const when = whenFrom_(b.date, cur[1]);
  x.sh.getRange(x.row, 2).setValue(when);
  x.sh.getRange(x.row, 4, 1, 12).setValues([[b.region, b.zone, b.base, b.distributor, b.brand, b.sku, p, dcode, bu, si ? si.code : cur[12], email, new Date()]]);
  return { ok: true };
}

function adminEditSales_(email, M, b) {
  const x = rowForEdit_('Sales_Log', SALES_HDR, b), cur = x.cur;
  const amt = Number(b.amount);
  if (!(amt >= 0)) return { ok: false, error: 'Enter the sales amount in BDT' };
  const same = b.region === cur[3] && b.zone === cur[4] && b.base === cur[5] && b.distributor === cur[6];
  const di = M.distInfo[locKey_(b)];
  if (!di && !same) return { ok: false, error: 'Pick Region, Zone, Base and Distributor from the lists.' };
  const when = whenFrom_(b.date, cur[1]);
  x.sh.getRange(x.row, 2).setValue(when);
  x.sh.getRange(x.row, 4, 1, 9).setValues([[b.region, b.zone, b.base, b.distributor, amt, di ? di.code : cur[8], di ? di.bu : cur[9], email, new Date()]]);
  return { ok: true };
}

// Per distributor: stock (latest value per SKU), secondary sales (sum of BDT), counts, last date.
function dashboard_() {
  const tz = Session.getScriptTimeZone(), agg = {};
  function get(r, z, b, d) {
    const k = [r, z, b, d].join('|');
    return agg[k] = agg[k] || { region: r, zone: z, base: b, distributor: d, stockSubs: 0, salesSubs: 0,
      totalPcs: 0, totalSales: 0, last: '', bu: '', skus: {}, ids: {} };
  }
  function fmt(t) { return Utilities.formatDate(new Date(t), tz, 'yyyy-MM-dd HH:mm'); }
  rows_('Stock_Items').forEach(function (r) {
    if (!r[0]) return;
    const a = get(r[3], r[4], r[5], r[6]);
    if (r[11]) a.bu = String(r[11]);
    if (!a.ids[r[0]]) { a.ids[r[0]] = 1; a.stockSubs++; }
    const t = fmt(r[1]); if (t > a.last) a.last = t;
    const k = r[7] + '|' + r[8], ts = new Date(r[1]).getTime(), p = Number(r[9]) || 0;
    const x = a.skus[k] = a.skus[k] || { brand: r[7], sku: r[8], stock: 0, ts: 0 };
    if (ts > x.ts) { x.ts = ts; x.stock = p; } else if (ts === x.ts) { x.stock += p; }
  });
  rows_('Sales_Log').forEach(function (r) {
    if (!r[0]) return;
    const a = get(r[3], r[4], r[5], r[6]); a.salesSubs++; a.totalSales += Number(r[7]) || 0;
    if (r[9]) a.bu = String(r[9]);
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
  SpreadsheetApp.getUi().createMenu('Stock App').addItem('Add / reset user', 'addUser').addItem('Set user role (admin / user)', 'setRole').addItem('Check master data', 'checkMaster').addToUi();
}
function ensureRoleHeader_(sh) {
  if (!String(sh.getRange(1, 5).getValue()).trim()) sh.getRange(1, 5).setValue('Role').setFontWeight('bold');
}
function addUser() {
  const ui = SpreadsheetApp.getUi();
  const e = ui.prompt('User email'); if (e.getSelectedButton() !== ui.Button.OK) return;
  const p = ui.prompt('Password'); if (p.getSelectedButton() !== ui.Button.OK) return;
  const n = ui.prompt('Display name'); if (n.getSelectedButton() !== ui.Button.OK) return;
  const a = ui.alert('Admin?', 'Make this user an admin?\nYes = can add and edit data on the web page.\nNo = normal user (can only view and download on the web).', ui.ButtonSet.YES_NO);
  const role = a === ui.Button.YES ? 'admin' : 'user';
  const sh = ss_().getSheetByName('Users'), em = e.getResponseText().trim().toLowerCase();
  ensureRoleHeader_(sh);
  const data = sh.getDataRange().getValues();
  for (let i = 1; i < data.length; i++) {
    if (String(data[i][0]).toLowerCase() === em) {
      sh.getRange(i + 1, 1, 1, 5).setValues([[em, hash_(p.getResponseText()), n.getResponseText(), true, role]]);
      ui.alert('User updated (' + role + ')'); return;
    }
  }
  sh.appendRow([em, hash_(p.getResponseText()), n.getResponseText(), true, role]);
  ui.alert('User added (' + role + ')');
}

// Change only the role of an existing user (password stays as it is).
function setRole() {
  const ui = SpreadsheetApp.getUi();
  const e = ui.prompt('Email of the user'); if (e.getSelectedButton() !== ui.Button.OK) return;
  const sh = ss_().getSheetByName('Users'), em = e.getResponseText().trim().toLowerCase();
  const data = sh.getDataRange().getValues();
  for (let i = 1; i < data.length; i++) {
    if (String(data[i][0]).toLowerCase() === em) {
      const a = ui.alert('Role', 'Make ' + em + ' an admin?\nYes = admin (can add and edit data on the web page).\nNo = normal user.', ui.ButtonSet.YES_NO);
      ensureRoleHeader_(sh);
      const role = a === ui.Button.YES ? 'admin' : 'user';
      sh.getRange(i + 1, 5).setValue(role);
      ui.alert(em + ' is now: ' + role); return;
    }
  }
  ui.alert('User not found: ' + em);
}

/* Shows what the app will load from the two dump tabs - run it after importing/refreshing them */
function checkMaster() {
  const ui = SpreadsheetApp.getUi(), M = loadMaster_(), st = M.stats;
  let zones = 0, bases = 0; const buCount = {};
  Object.keys(M.regions).forEach(function (r) {
    zones += Object.keys(M.regions[r]).length;
    Object.keys(M.regions[r]).forEach(function (z) { bases += Object.keys(M.regions[r][z]).length; });
  });
  Object.keys(M.distInfo).forEach(function (k) { const b = M.distInfo[k].bu || '(none)'; buCount[b] = (buCount[b] || 0) + 1; });
  const skuBu = Object.keys(M.buBrands).map(function (b) { return b + ': ' + Object.keys(M.buBrands[b]).length + ' brands'; }).join(', ');
  let t = 'CUSTOMER MASTER\n';
  t += st.custTabs.length ? '  tabs: ' + st.custTabs.join(', ') + '\n  active distributors: ' + st.dist + '  (skipped deprecated/blank/duplicate: ' + st.distSkipped + ')\n  regions ' +
       Object.keys(M.regions).length + ', zones ' + zones + ', bases ' + bases + '\n  by BU: ' + Object.keys(buCount).map(function (b) { return b + ' ' + buCount[b]; }).join(', ') + '\n'
     : '  NOT FOUND - import the customer master file (needs columns REGION_BASE, ASM_BASE, BASE_NAME, DISTRIBUTOR_CODE, DISTRIBUTOR_NAME).\n';
  t += '\nPRODUCT MASTER\n';
  t += st.skuTabs.length ? '  tabs: ' + st.skuTabs.join(', ') + '\n  active SKUs: ' + st.sku + '  (skipped inactive/blank/duplicate: ' + st.skuSkipped + ')\n  brands ' +
       Object.keys(M.brands).length + '\n  ' + skuBu + '\n'
     : '  NOT FOUND - import the product master file (needs columns SKU_GROUP, ERP_SKU_CODE, ERP_SKU_NAME).\n';
  ui.alert('Master data check', t, ui.ButtonSet.OK);
}
