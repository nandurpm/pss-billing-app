const ADMIN_USER = 'ps';
const ADMIN_PASS = '123';
const DB_NAME = 'PSS_BILLING_DB';
const DB_VERSION = 1;

const DEFAULT_SETTINGS = {
  businessName: 'Purple Signature Salon',
  tagline: 'Skin · Hair · Bridal · Nails',
  phone: '',
  gst: '',
  address: '',
  qr: '',
  services: [
    { name: 'Hair Cut', rate: 250 },
    { name: 'Hair Spa', rate: 1200 },
    { name: 'Facial', rate: 900 },
    { name: 'Threading', rate: 80 },
    { name: 'Bridal Makeup', rate: 8500 },
    { name: 'Nail Art', rate: 600 },
    { name: 'Bleach', rate: 450 },
    { name: 'Product Sale', rate: 500 }
  ]
};

const $ = id => document.getElementById(id);
let db = null;
let settings = loadSettings();
let items = [];
let dirty = false;
let adminOK = false;

function loadSettings() {
  try {
    return { ...DEFAULT_SETTINGS, ...JSON.parse(localStorage.getItem('pssSettings') || '{}') };
  } catch (e) {
    return JSON.parse(JSON.stringify(DEFAULT_SETTINGS));
  }
}

function storeSettings() {
  localStorage.setItem('pssSettings', JSON.stringify(settings));
}

function openDB() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = event => {
      const database = event.target.result;
      if (!database.objectStoreNames.contains('bills')) {
        const store = database.createObjectStore('bills', { keyPath: 'invoiceNo' });
        store.createIndex('billDate', 'billDate', { unique: false });
        store.createIndex('mobile', 'mobile', { unique: false });
        store.createIndex('savedAt', 'savedAt', { unique: false });
      }
    };
    req.onsuccess = event => { db = event.target.result; resolve(db); };
    req.onerror = () => reject(req.error);
  });
}

function store(name, mode = 'readonly') {
  return db.transaction(name, mode).objectStore(name);
}

function putBill(bill) {
  return new Promise((resolve, reject) => {
    const req = store('bills', 'readwrite').put(bill);
    req.onsuccess = () => resolve();
    req.onerror = () => reject(req.error);
  });
}

function allBills() {
  return new Promise((resolve, reject) => {
    const req = store('bills').getAll();
    req.onsuccess = () => resolve(req.result || []);
    req.onerror = () => reject(req.error);
  });
}

function removeBill(id) {
  return new Promise((resolve, reject) => {
    const req = store('bills', 'readwrite').delete(id);
    req.onsuccess = () => resolve();
    req.onerror = () => reject(req.error);
  });
}

function money(value) {
  return '₹' + (Number(value) || 0).toFixed(2);
}

function today() {
  return new Date().toISOString().slice(0, 10);
}

function makeInvoiceNo() {
  const d = new Date();
  return 'PSS-' + d.getFullYear() + String(d.getMonth() + 1).padStart(2, '0') + String(d.getDate()).padStart(2, '0') + '-' + String(Date.now()).slice(-5);
}

function safe(value) {
  return String(value ?? '').replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' }[ch]));
}

function tick() {
  $('clock').textContent = new Date().toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' });
}

function applyBrand() {
  $('topBusiness').textContent = settings.businessName || DEFAULT_SETTINGS.businessName;
  $('topTagline').textContent = settings.tagline || DEFAULT_SETTINGS.tagline;
}

function bindInputs() {
  ['invoiceNo', 'billDate', 'customer', 'mobile', 'payment', 'discount', 'staff', 'tax', 'notes'].forEach(id => {
    $(id).addEventListener('input', () => { dirty = true; renderInvoice(); });
  });
  $('itemSelect').addEventListener('change', () => {
    const service = settings.services[Number($('itemSelect').value)];
    if (service) $('itemRate').value = service.rate;
  });
  $('searchBox').addEventListener('input', renderHistory);
  $('searchDate').addEventListener('input', renderHistory);
  $('qrFile').addEventListener('change', readQrFile);
}

async function init() {
  await openDB();
  applyBrand();
  bindInputs();
  $('invoiceNo').value = makeInvoiceNo();
  $('billDate').value = today();
  renderServiceButtons();
  selectService(0);
  addSelectedItem();
  tick();
  setInterval(tick, 1000);
  renderItems();
  renderInvoice();
  renderHistory();
}

function renderServiceButtons() {
  $('serviceButtons').innerHTML = settings.services.map((service, index) =>
    `<button class="serviceBtn" onclick="quickService(${index})">${safe(service.name)}<span>${money(service.rate)}</span></button>`
  ).join('');
  $('itemSelect').innerHTML = settings.services.map((service, index) =>
    `<option value="${index}">${safe(service.name)} - ${money(service.rate)}</option>`
  ).join('');
}

function selectService(index) {
  const service = settings.services[index];
  if (!service) return;
  $('itemSelect').value = index;
  $('itemQty').value = 1;
  $('itemRate').value = service.rate;
}

function quickService(index) {
  selectService(index);
  addSelectedItem();
}

function addSelectedItem() {
  const service = settings.services[Number($('itemSelect').value)] || { name: 'Item', rate: 0 };
  items.push({ name: service.name, qty: Number($('itemQty').value) || 1, rate: Number($('itemRate').value) || 0 });
  dirty = true;
  renderItems();
  renderInvoice();
}

function removeItem(index) {
  items.splice(index, 1);
  dirty = true;
  renderItems();
  renderInvoice();
}

function renderItems() {
  $('items').innerHTML = items.map((item, index) => `
    <tr>
      <td><input value="${safe(item.name)}" oninput="items[${index}].name=this.value;dirty=true;renderInvoice()"></td>
      <td><input type="number" min="1" value="${item.qty}" oninput="items[${index}].qty=Number(this.value)||1;dirty=true;renderInvoice()"></td>
      <td><input type="number" min="0" value="${item.rate}" oninput="items[${index}].rate=Number(this.value)||0;dirty=true;renderInvoice()"></td>
      <td class="num"><b>${money((Number(item.qty) || 0) * (Number(item.rate) || 0))}</b></td>
      <td><button class="btn danger small" onclick="removeItem(${index})">×</button></td>
    </tr>
  `).join('');
}

function totals() {
  const subtotal = items.reduce((sum, item) => sum + (Number(item.qty) || 0) * (Number(item.rate) || 0), 0);
  const discount = Number($('discount').value) || 0;
  const taxable = Math.max(0, subtotal - discount);
  const taxAmount = taxable * ((Number($('tax').value) || 0) / 100);
  return { subtotal, discount, taxAmount, grand: taxable + taxAmount };
}

function billData() {
  return {
    invoiceNo: $('invoiceNo').value.trim() || makeInvoiceNo(),
    billDate: $('billDate').value || today(),
    customer: $('customer').value.trim() || 'Walk-in Customer',
    mobile: $('mobile').value.trim(),
    payment: $('payment').value,
    tax: Number($('tax').value) || 0,
    discount: Number($('discount').value) || 0,
    staff: $('staff').value.trim(),
    notes: $('notes').value.trim(),
    items: items.filter(item => item.name || item.rate),
    ...totals(),
    savedAt: new Date().toISOString()
  };
}

function renderInvoice() {
  const bill = billData();
  const qr = settings.qr ? `<img class="qr" src="${settings.qr}" alt="Payment QR"><div class="footer-note">Scan to pay</div>` : '';
  $('invoicePreview').innerHTML = `
    <div class="invoiceLogo"><img src="logo.svg" alt="Purple Signature Salon"></div>
    <div class="invoiceHead">
      <div><h3>${safe(settings.businessName)}</h3><p>${safe(settings.tagline)}</p><p>${safe(settings.address || '')}</p><p>${safe(settings.phone || '')}</p></div>
      <div class="num"><p><b>Invoice</b></p><p>${safe(bill.invoiceNo)}</p><p>${safe(bill.billDate)}</p><p>${safe(settings.gst || '')}</p></div>
    </div>
    <p><b>Customer:</b> ${safe(bill.customer)} ${bill.mobile ? '· ' + safe(bill.mobile) : ''}</p>
    <table><thead><tr><th>Item</th><th>Qty</th><th>Rate</th><th>Total</th></tr></thead><tbody>${bill.items.map(item => `
      <tr><td>${safe(item.name)}</td><td class="num">${item.qty}</td><td class="num">${money(item.rate)}</td><td class="num">${money((Number(item.qty)||0)*(Number(item.rate)||0))}</td></tr>
    `).join('')}</tbody></table>
    <div class="summary">
      <div class="sumrow"><span>Subtotal</span><b>${money(bill.subtotal)}</b></div>
      <div class="sumrow"><span>Discount</span><b>${money(bill.discount)}</b></div>
      <div class="sumrow"><span>Tax ${bill.tax}%</span><b>${money(bill.taxAmount)}</b></div>
      <div class="sumrow grand"><span>Grand Total</span><strong>${money(bill.grand)}</strong></div>
    </div>
    <p><b>Payment:</b> ${safe(bill.payment)} &nbsp; <b>Staff:</b> ${safe(bill.staff || '-')}</p>
    <p><b>Notes:</b> ${safe(bill.notes || '-')}</p>
    ${qr}<div class="footer-note">Thank you. Visit again.</div>
  `;
}

async function saveBill() {
  const bill = billData();
  if (!bill.items.length) {
    alert('Add at least one service or product.');
    return;
  }
  await putBill(bill);
  dirty = false;
  await renderHistory();
  if (confirm('Bill saved. Open new bill?')) startNew(false);
}

function startNew(ask) {
  if (ask && dirty && !confirm('Clear current unsaved bill and open new bill?')) return;
  items = [];
  $('invoiceNo').value = makeInvoiceNo();
  $('billDate').value = today();
  $('customer').value = '';
  $('mobile').value = '';
  $('payment').value = 'Cash';
  $('discount').value = 0;
  $('staff').value = '';
  $('tax').value = 0;
  $('notes').value = '';
  selectService(0);
  dirty = false;
  renderItems();
  renderInvoice();
}

async function renderHistory() {
  const query = $('searchBox').value.trim().toLowerCase();
  const date = $('searchDate').value;
  let bills = await allBills();
  bills.sort((a, b) => String(b.savedAt || '').localeCompare(String(a.savedAt || '')));
  if (query) bills = bills.filter(bill => [bill.invoiceNo, bill.customer, bill.mobile, bill.payment, bill.staff].join(' ').toLowerCase().includes(query));
  if (date) bills = bills.filter(bill => bill.billDate === date);
  $('billCount').textContent = bills.length + ' bill' + (bills.length === 1 ? '' : 's');
  $('history').innerHTML = bills.length ? bills.slice(0, 500).map(bill => `
    <div class="historyItem">
      <div class="historyTop"><div><b>${safe(bill.invoiceNo)}</b><div class="small">${safe(bill.customer)} · ${safe(bill.mobile || 'No mobile')}</div><div class="small">${safe(bill.billDate)} · ${safe(bill.payment)}</div></div><div class="num"><b>${money(bill.grand)}</b></div></div>
      <div class="actions history-actions"><button class="btn ghost small" onclick="loadBill('${safe(bill.invoiceNo)}')">Load</button><button class="btn danger small" onclick="deleteBill('${safe(bill.invoiceNo)}')">Delete</button></div>
    </div>
  `).join('') : '<div class="small">No saved bills found.</div>';
}

async function loadBill(id) {
  const bills = await allBills();
  const bill = bills.find(item => item.invoiceNo === id);
  if (!bill) return;
  $('invoiceNo').value = bill.invoiceNo;
  $('billDate').value = bill.billDate;
  $('customer').value = bill.customer || '';
  $('mobile').value = bill.mobile || '';
  $('payment').value = bill.payment || 'Cash';
  $('discount').value = bill.discount || 0;
  $('staff').value = bill.staff || '';
  $('tax').value = bill.tax || 0;
  $('notes').value = bill.notes || '';
  items = (bill.items || []).map(item => ({ name: item.name, qty: item.qty, rate: item.rate }));
  dirty = false;
  renderItems();
  renderInvoice();
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

async function deleteBill(id) {
  if (!confirm('Delete invoice ' + id + '?')) return;
  await removeBill(id);
  renderHistory();
}

function clearSearch() {
  $('searchBox').value = '';
  $('searchDate').value = '';
  renderHistory();
}

async function shareBill() {
  const bill = billData();
  const text = `${settings.businessName}\nInvoice: ${bill.invoiceNo}\nCustomer: ${bill.customer}\nTotal: ${money(bill.grand)}\nPayment: ${bill.payment}`;
  if (navigator.share) {
    try { await navigator.share({ title: 'Bill', text }); } catch (e) {}
  } else {
    await navigator.clipboard.writeText(text);
    alert('Bill text copied.');
  }
}

async function exportBills() {
  const bills = await allBills();
  const blob = new Blob([JSON.stringify({ settings, bills }, null, 2)], { type: 'application/json' });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = 'pss-billing-backup-' + today() + '.json';
  link.click();
  URL.revokeObjectURL(link.href);
}

function openAdmin() {
  $('adminModal').classList.add('show');
  $('adminPass').value = '';
  if (adminOK) showAdminPanel();
  else {
    $('loginBox').classList.remove('hidden');
    $('adminPanel').classList.add('hidden');
  }
}

function closeAdmin() {
  $('adminModal').classList.remove('show');
}

function adminLogin() {
  if ($('adminUser').value === ADMIN_USER && $('adminPass').value === ADMIN_PASS) {
    adminOK = true;
    showAdminPanel();
  } else {
    alert('Wrong username or password.');
  }
}

function adminLogout() {
  adminOK = false;
  $('adminUser').value = '';
  $('adminPass').value = '';
  $('loginBox').classList.remove('hidden');
  $('adminPanel').classList.add('hidden');
}

function showAdminPanel() {
  $('loginBox').classList.add('hidden');
  $('adminPanel').classList.remove('hidden');
  $('setBusiness').value = settings.businessName || '';
  $('setTagline').value = settings.tagline || '';
  $('setPhone').value = settings.phone || '';
  $('setGst').value = settings.gst || '';
  $('setAddress').value = settings.address || '';
  $('qrPreviewBox').innerHTML = settings.qr ? `<img class="qr" src="${settings.qr}" alt="QR preview">` : '';
  renderServiceAdmin();
}

function renderServiceAdmin() {
  $('serviceAdminRows').innerHTML = settings.services.map((service, index) => `
    <tr><td><input value="${safe(service.name)}" oninput="settings.services[${index}].name=this.value"></td><td><input type="number" min="0" value="${service.rate}" oninput="settings.services[${index}].rate=Number(this.value)||0"></td><td><button class="btn danger small" onclick="removeServiceRow(${index})">×</button></td></tr>
  `).join('');
}

function addServiceRow() {
  settings.services.push({ name: 'New Service', rate: 0 });
  renderServiceAdmin();
}

function removeServiceRow(index) {
  if (settings.services.length <= 1) {
    alert('At least one service is required.');
    return;
  }
  settings.services.splice(index, 1);
  renderServiceAdmin();
}

function saveSettingsFromAdmin() {
  settings.businessName = $('setBusiness').value.trim() || DEFAULT_SETTINGS.businessName;
  settings.tagline = $('setTagline').value.trim() || DEFAULT_SETTINGS.tagline;
  settings.phone = $('setPhone').value.trim();
  settings.gst = $('setGst').value.trim();
  settings.address = $('setAddress').value.trim();
  settings.services = settings.services.filter(service => service.name.trim()).map(service => ({ name: service.name.trim(), rate: Number(service.rate) || 0 }));
  storeSettings();
  applyBrand();
  renderServiceButtons();
  renderInvoice();
  alert('Admin settings saved.');
}

function readQrFile(event) {
  const file = event.target.files && event.target.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    settings.qr = reader.result;
    $('qrPreviewBox').innerHTML = `<img class="qr" src="${settings.qr}" alt="QR preview">`;
  };
  reader.readAsDataURL(file);
}

init().catch(error => {
  document.body.innerHTML = '<div style="padding:20px;color:white">App failed to start: ' + safe(error.message) + '</div>';
});
