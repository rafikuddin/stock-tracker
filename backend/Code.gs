/**
 * Stock Tracker API - Google Apps Script (bound to the Google Sheet)
 * Master data comes straight from your two dump files, imported as tabs of this Sheet:
 *   CUSTOMER_MASTER (tabs Adhesive / Corporate / Thinner)  -> Region > Zone > Base > Distributor
 *   PRODUCT_MASTER  (tab SKU_MASTER)                        -> Brand > SKU
 * Tabs are found by their column headers, so tab names do not matter.
 * Other tabs: Users, Stock_Items, Sales_Log (created automatically).
 * Deploy: Deploy > New deployment > Web app > Execute as: Me, Who has access: Anyone
 */
const SALT = 'CHANGE_THIS_SECRET_SALT';   // KEEP the value you already use
const TOKEN_TTL = 21600;
// (TOKEN_TTL above is in seconds; CacheService allows max 6h)
const STOCK_HDR = ['SubmissionID','Timestamp','Email','Region','Zone','Base','Distributor','Brand','SKU','Pcs','DistributorCode','BU','SKUCode'];
const SALES_HDR = ['SubmissionID','Timestamp','Email','Region','Zone','Base','Distributor','SecondarySalesBDT','DistributorCode','BU'];

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
    return { id: String(r[0]), ts: ts(r[1]), by: r[2], region: r[3], zone: r[4], base: r[5], dist: r[6], brand: r[7], sku: r[8], pcs: Number(r[9]) || 0, dcode: r[10] || '', bu: r[11] || '', skucode: r[12] || '' };
  });
  const sales = rows_('Sales_Log').filter(function (r) { return r[0]; }).map(function (r) {
    return { id: String(r[0]), ts: ts(r[1]), by: r[2], region: r[3], zone: r[4], base: r[5], dist: r[6], amount: Number(r[7]) || 0, dcode: r[8] || '', bu: r[9] || '' };
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

// body: {region, zone, base, distributor, items:[{brand, sku, pcs}]}
function submitStock_(email, b) {
  if (!b.distributor || !b.items || !b.items.length) return { ok: false, error: 'Distributor and items required' };
  const M = loadMaster_();
  const di = M.distInfo[[b.region, b.zone, b.base, b.distributor].join('|')] || { code: '', bu: '' };
  const lock = LockService.getScriptLock(); lock.waitLock(20000);
  try {
    const id = Utilities.getUuid(), now = new Date();
    const rows = b.items.map(function (i) {
      const p = Number(i.pcs);
      if (!(p > 0)) throw new Error('Invalid pcs');
      const si = M.skuInfo[i.brand + '|' + i.sku] || { code: '', bu: '' };
      if (di.bu && M.buBrands[di.bu] && si.bu && si.bu !== di.bu)
        throw new Error('"' + i.sku + '" is a ' + si.bu + ' SKU, but this distributor is ' + di.bu + '. Remove it and try again.');
      return [id, now, email, b.region, b.zone, b.base, b.distributor, i.brand, i.sku, p, di.code, di.bu, si.code];
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
  const di = loadMaster_().distInfo[[b.region, b.zone, b.base, b.distributor].join('|')] || { code: '', bu: '' };
  const lock = LockService.getScriptLock(); lock.waitLock(20000);
  try {
    const id = Utilities.getUuid();
    sheet_('Sales_Log', SALES_HDR).appendRow([id, new Date(), email, b.region, b.zone, b.base, b.distributor, amt, di.code, di.bu]);
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
  SpreadsheetApp.getUi().createMenu('Stock App').addItem('Add / reset user', 'addUser').addItem('Check master data', 'checkMaster').addToUi();
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
