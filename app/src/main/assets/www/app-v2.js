'use strict';

const DB_NAME = 'PSS_BILLING_DB';
const STORE_NAME = 'bills';
const DEFAULT_ADMIN_PIN_HASH = '1411cfac743a15d35bb3b96f78cb15654d07bb72a9ca2a5e524b3256f8b4c60a';

const DEFAULT_SETTINGS = {
  businessName: 'Purple Signature Salon',
  tagline: 'Skin · Hair · Bridal · Nails',
  phone: '',
  address: '',
  qr: '',
  gstEnabled: false,
  gstPercent: 0,
  adminPinHash: DEFAULT_ADMIN_PIN_HASH,
  staff: [
    { id: 'ABITHA', name: 'ABITHA', active: true },
    { id: 'NIVETHA', name: 'NIVETHA', active: true }
  ],
  services: [
    { id: 'hair-cut', name: 'Hair Cut', category: 'Hair', rate: 250, active: true },
    { id: 'hair-spa', name: 'Hair Spa', category: 'Hair', rate: 1200, active: true },
    { id: 'facial', name: 'Facial', category: 'Skin', rate: 900, active: true },
    { id: 'threading', name: 'Threading', category: 'Skin', rate: 80, active: true },
    { id: 'bridal', name: 'Bridal Makeup', category: 'Bridal', rate: 8500, active: true },
    { id: 'nail-art', name: 'Nail Art', category: 'Nails', rate: 600, active: true },
    { id: 'bleach', name: 'Bleach', category: 'Skin', rate: 450, active: true },
    { id: 'product', name: 'Product Sale', category: 'Products', rate: 500, active: true }
  ]
};

const $ = id => document.getElementById(id);
let db = null;
let settings = loadSettings();
let items = [];
let viewedBill = null;
let currentReport = null;
let dirty = false;
let editingInvoiceNo = null;
let editingCreatedAt = null;
const reservedInvoiceNumbers = new Set();

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function safe(value) {
  return String(value ?? '').replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;'
  }[char]));
}

function slug(value, fallback) {
  const normalized = String(value || '').trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
  return normalized || fallback;
}

function money(value) {
  return '₹' + (Number(value) || 0).toFixed(2);
}

function today() {
  const now = new Date();
  const offset = now.getTimezoneOffset();
  return new Date(now.getTime() - offset * 60000).toISOString().slice(0, 10);
}

function paymentLabel(mode) {
  if (mode === 'UPI') return 'UPI / GPay';
  if (mode === 'CARD') return 'Card';
  if (mode === 'CREDIT') return 'Credit';
  return 'Cash';
}

function normalizePayment(value) {
  const text = String(value || 'CASH').toUpperCase();
  if (text.includes('UPI') || text.includes('GPAY')) return 'UPI';
  if (text.includes('CARD')) return 'CARD';
  if (text.includes('CREDIT')) return 'CREDIT';
  return 'CASH';
}

function normalizeIndianMobile(value) {
  let digits = String(value || '').replace(/\D/g, '');
  if (!digits) return '';
  if (digits.length === 11 && digits.startsWith('0')) digits = digits.slice(1);
  if (digits.length === 12 && digits.startsWith('91')) digits = digits.slice(2);
  return /^\d{10}$/.test(digits) ? digits : null;
}

function normalizeSettings(raw) {
  const merged = { ...clone(DEFAULT_SETTINGS), ...(raw || {}) };

  const incomingStaff = Array.isArray(raw && raw.staff) && raw.staff.length ? raw.staff : clone(DEFAULT_SETTINGS.staff);
  merged.staff = incomingStaff.map((member, index) => {
    const source = typeof member === 'string' ? { name: member } : (member || {});
    let name = String(source.name || source.id || ('STAFF ' + (index + 1))).trim().toUpperCase();
    let id = String(source.id || name).trim().toUpperCase();
    if (name === 'NIVEDA') name = 'NIVETHA';
    if (id === 'NIVEDA') id = 'NIVETHA';
    return { id: id || ('STAFF-' + index), name: name || ('STAFF ' + (index + 1)), active: source.active !== false };
  });

  const incomingServices = Array.isArray(raw && raw.services) && raw.services.length ? raw.services : clone(DEFAULT_SETTINGS.services);
  merged.services = incomingServices.map((service, index) => ({
    id: String(service.id || slug(service.name, 'service-' + index)),
    name: String(service.name || ('Service ' + (index + 1))).trim(),
    category: String(service.category || 'Other').trim(),
    rate: Math.max(0, Number(service.rate) || 0),
    active: service.active !== false
  }));

  merged.gstEnabled = Boolean(merged.gstEnabled);
  merged.gstPercent = Math.min(100, Math.max(0, Number(merged.gstPercent) || 0));
  merged.adminPinHash = String(merged.adminPinHash || DEFAULT_ADMIN_PIN_HASH);
  return merged;
}

function loadSettings() {
  try {
    const stored = JSON.parse(localStorage.getItem('pssV2Settings') || localStorage.getItem('pssSettings') || '{}');
    const normalized = normalizeSettings(stored);
    localStorage.setItem('pssV2Settings', JSON.stringify(normalized));
    return normalized;
  } catch (error) {
    return clone(DEFAULT_SETTINGS);
  }
}

function saveSettingsLocal() {
  settings = normalizeSettings(settings);
  localStorage.setItem('pssV2Settings', JSON.stringify(settings));
}

function createOrUpgradeSchema(database, transaction) {
  let store;
  if (!database.objectStoreNames.contains(STORE_NAME)) {
    store = database.createObjectStore(STORE_NAME, { keyPath: 'invoiceNo' });
  } else {
    store = transaction.objectStore(STORE_NAME);
  }
  if (!store.indexNames.contains('billDate')) store.createIndex('billDate', 'billDate', { unique: false });
  if (!store.indexNames.contains('mobile')) store.createIndex('mobile', 'mobile', { unique: false });
  if (!store.indexNames.contains('savedAt')) store.createIndex('savedAt', 'savedAt', { unique: false });
  if (!store.indexNames.contains('staffId')) store.createIndex('staffId', 'staffId', { unique: false });
  if (!store.indexNames.contains('payment')) store.createIndex('payment', 'payment', { unique: false });
}

function openDatabaseRequest(version) {
  return new Promise((resolve, reject) => {
    const request = version ? indexedDB.open(DB_NAME, version) : indexedDB.open(DB_NAME);
    request.onupgradeneeded = event => createOrUpgradeSchema(event.target.result, event.target.transaction);
    request.onsuccess = event => resolve(event.target.result);
    request.onerror = () => reject(request.error || new Error('Unable to open billing database'));
    request.onblocked = () => reject(new Error('Database upgrade is blocked. Close and reopen the app.'));
  });
}

async function openDb() {
  let database = await openDatabaseRequest();
  let needsUpgrade = !database.objectStoreNames.contains(STORE_NAME);

  if (!needsUpgrade) {
    try {
      const store = database.transaction(STORE_NAME, 'readonly').objectStore(STORE_NAME);
      const requiredIndexes = ['billDate', 'mobile', 'savedAt', 'staffId', 'payment'];
      needsUpgrade = requiredIndexes.some(name => !store.indexNames.contains(name));
    } catch (error) {
      needsUpgrade = true;
    }
  }

  if (needsUpgrade) {
    const nextVersion = Math.max(1, database.version + 1);
    database.close();
    database = await openDatabaseRequest(nextVersion);
  }

  if (!database.objectStoreNames.contains(STORE_NAME)) {
    database.close();
    throw new Error('Billing database repair failed. The bills store is still missing.');
  }

  database.onversionchange = () => {
    database.close();
    toast('App database was updated. Reopen the app.', 'error');
  };

  db = database;
}

function getStore(mode = 'readonly') {
  if (!db || !db.objectStoreNames.contains(STORE_NAME)) {
    throw new Error('Billing database is not ready');
  }
  return db.transaction(STORE_NAME, mode).objectStore(STORE_NAME);
}

function allBills() {
  return new Promise((resolve, reject) => {
    try {
      const request = getStore().getAll();
      request.onsuccess = () => resolve(request.result || []);
      request.onerror = () => reject(request.error || new Error('Unable to read bills'));
    } catch (error) {
      reject(error);
    }
  });
}

function getBill(invoiceNumber) {
  return new Promise((resolve, reject) => {
    try {
      const request = getStore().get(invoiceNumber);
      request.onsuccess = () => resolve(request.result || null);
      request.onerror = () => reject(request.error || new Error('Unable to read bill'));
    } catch (error) {
      reject(error);
    }
  });
}

function addBill(bill) {
  return new Promise((resolve, reject) => {
    try {
      const request = getStore('readwrite').add(bill);
      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error || new Error('Unable to add bill'));
    } catch (error) {
      reject(error);
    }
  });
}

function putBill(bill) {
  return new Promise((resolve, reject) => {
    try {
      const request = getStore('readwrite').put(bill);
      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error || new Error('Unable to update bill'));
    } catch (error) {
      reject(error);
    }
  });
}

function deleteBillDb(invoiceNumber) {
  return new Promise((resolve, reject) => {
    try {
      const request = getStore('readwrite').delete(invoiceNumber);
      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error || new Error('Unable to delete bill'));
    } catch (error) {
      reject(error);
    }
  });
}

async function migrateBills() {
  const bills = await allBills();
  for (const original of bills) {
    const bill = { ...original };
    let changed = false;

    bill.payment = normalizePayment(bill.payment);
    if (bill.payment !== original.payment) changed = true;

    if (!bill.status) {
      bill.status = 'SAVED';
      changed = true;
    }

    if (!bill.createdAt) {
      bill.createdAt = (bill.billDate || today()) + 'T00:00:00';
      changed = true;
    }

    if (!bill.savedAt) {
      bill.savedAt = bill.createdAt;
      changed = true;
    }

    const oldStaff = String(bill.staff || bill.staffId || '').trim().toUpperCase();
    if (oldStaff === 'NIVEDA') {
      bill.staff = 'NIVETHA';
      bill.staffId = 'NIVETHA';
      changed = true;
    } else if (!bill.staffId && oldStaff) {
      bill.staffId = oldStaff;
      changed = true;
    }

    if (!Array.isArray(bill.items)) {
      bill.items = [];
      changed = true;
    }

    if (changed) await putBill(bill);
  }
}

function formatInvoiceBase(date = new Date()) {
  return String(date.getDate()).padStart(2, '0') +
    String(date.getMonth() + 1).padStart(2, '0') +
    date.getFullYear() + '-' +
    String(date.getHours()).padStart(2, '0') + '_' +
    String(date.getMinutes()).padStart(2, '0');
}

async function nextInvoiceNo() {
  const base = formatInvoiceBase();
  const matcher = new RegExp('^' + base.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '(?:-(\\d{3}))?$');
  const bills = await allBills();
  let maxSequence = 0;

  for (const bill of bills) {
    const match = matcher.exec(String(bill.invoiceNo || ''));
    if (match) maxSequence = Math.max(maxSequence, Number(match[1] || 0));
  }

  for (const reserved of reservedInvoiceNumbers) {
    const match = matcher.exec(reserved);
    if (match) maxSequence = Math.max(maxSequence, Number(match[1] || 0));
  }

  const number = base + '-' + String(maxSequence + 1).padStart(3, '0');
  reservedInvoiceNumbers.add(number);
  return number;
}

async function assignNewInvoiceNumber() {
  const current = $('invoiceNo').value;
  if (current) reservedInvoiceNumbers.delete(current);
  $('invoiceNo').value = await nextInvoiceNo();
}

function activeStaff() {
  return settings.staff.filter(member => member.active !== false);
}

function activeServices() {
  return settings.services.filter(service => service.active !== false);
}

function settingsSnapshot() {
  return {
    businessName: settings.businessName,
    tagline: settings.tagline,
    phone: settings.phone,
    address: settings.address,
    qr: settings.qr,
    gstEnabled: Boolean(settings.gstEnabled),
    gstPercent: Number(settings.gstPercent) || 0
  };
}

function subtotalValue() {
  return items.reduce((sum, item) => sum + Math.max(0, Number(item.qty) || 0) * Math.max(0, Number(item.rate) || 0), 0);
}

function normalizedDiscount(subtotal) {
  const raw = Number($('discount').value);
  return Math.min(subtotal, Math.max(0, Number.isFinite(raw) ? raw : 0));
}

function calculateTotals(updateDiscountField = false) {
  const subtotal = subtotalValue();
  const discount = normalizedDiscount(subtotal);
  if (updateDiscountField && Number($('discount').value) !== discount) $('discount').value = discount;
  const taxable = Math.max(0, subtotal - discount);
  const tax = settings.gstEnabled ? Math.min(100, Math.max(0, Number(settings.gstPercent) || 0)) : 0;
  const taxAmount = taxable * tax / 100;
  return { subtotal, discount, tax, taxAmount, grand: taxable + taxAmount };
}

function currentStaffRecord() {
  const staffId = $('staff').value;
  return settings.staff.find(member => member.id === staffId) || { id: staffId, name: staffId };
}

function billData() {
  const staff = currentStaffRecord();
  const normalizedMobile = normalizeIndianMobile($('mobile').value);
  return {
    invoiceNo: $('invoiceNo').value.trim(),
    billDate: $('billDate').value || today(),
    createdAt: editingCreatedAt || new Date().toISOString(),
    savedAt: new Date().toISOString(),
    customer: $('customer').value.trim() || 'Walk-in Customer',
    mobile: normalizedMobile === null ? $('mobile').value.trim() : normalizedMobile,
    staff: staff.name || staff.id,
    staffId: staff.id,
    payment: normalizePayment($('payment').value),
    notes: $('notes').value.trim(),
    items: items.map(item => ({
      serviceId: item.serviceId || slug(item.name, 'service'),
      name: String(item.name || 'Item'),
      category: String(item.category || 'Other'),
      qty: Math.max(0, Number(item.qty) || 0),
      rate: Math.max(0, Number(item.rate) || 0),
      total: Math.max(0, Number(item.qty) || 0) * Math.max(0, Number(item.rate) || 0)
    })),
    ...calculateTotals(),
    status: 'SAVED',
    settingsSnapshot: settingsSnapshot()
  };
}

function toast(message, type = '') {
  const element = $('appToast');
  element.textContent = message;
  element.className = 'toast-message ' + type + ' show';
  clearTimeout(element._timer);
  element._timer = setTimeout(() => element.classList.remove('show'), 3000);
}

function markError(element) {
  if (!element) return;
  element.classList.add('field-error');
  element.scrollIntoView({ behavior: 'smooth', block: 'center' });
  try { element.focus(); } catch (error) {}
  setTimeout(() => element.classList.remove('field-error'), 2500);
}

function validateCurrentBill(requireMobileForShare = false) {
  if (!$('staff').value) {
    toast('Select a staff member before continuing', 'error');
    markError($('staff'));
    return false;
  }

  if (!items.length) {
    toast('Add at least one service before continuing', 'error');
    $('serviceButtons').scrollIntoView({ behavior: 'smooth', block: 'center' });
    return false;
  }

  const normalizedMobile = normalizeIndianMobile($('mobile').value);
  if ($('mobile').value.trim() && normalizedMobile === null) {
    toast('Enter a valid 10-digit Indian mobile number', 'error');
    markError($('mobile'));
    return false;
  }

  if (requireMobileForShare && !normalizedMobile) {
    toast('Customer mobile number is required for WhatsApp sharing', 'error');
    markError($('mobile'));
    return false;
  }

  const subtotal = subtotalValue();
  const discount = Number($('discount').value);
  if (!Number.isFinite(discount) || discount < 0 || discount > subtotal) {
    $('discount').value = normalizedDiscount(subtotal);
    toast('Discount was corrected to a valid amount', 'error');
    renderInvoice();
    return false;
  }

  return true;
}

function renderNav() {
  document.querySelectorAll('.nav-btn').forEach(button => {
    button.onclick = () => showPage(button.dataset.page);
  });
  $('menuBtn').onclick = () => {
    $('sidebar').classList.add('open');
    $('drawerBackdrop').classList.add('show');
  };
  $('drawerBackdrop').onclick = closeDrawer;
}

function closeDrawer() {
  $('sidebar').classList.remove('open');
  $('drawerBackdrop').classList.remove('show');
}

async function showPage(page) {
  document.querySelectorAll('.nav-btn').forEach(button => button.classList.toggle('active', button.dataset.page === page));
  document.querySelectorAll('.page').forEach(section => section.classList.toggle('active', section.id === 'page-' + page));
  closeDrawer();
  if (page === 'reports') await generateReport();
  if (page === 'settings' && sessionStorage.getItem('pssSettingsUnlocked') === '1') openSettings();
}

function renderStaffOptions() {
  const selectedBilling = $('staff').value;
  const selectedReport = $('reportStaff').value;
  const staff = activeStaff();
  $('staff').innerHTML = '<option value="">Select staff</option>' + staff.map(member => `<option value="${safe(member.id)}">${safe(member.name)}</option>`).join('');
  $('reportStaff').innerHTML = '<option value="ALL">All Staff</option>' + staff.map(member => `<option value="${safe(member.id)}">${safe(member.name)}</option>`).join('');
  if (staff.some(member => member.id === selectedBilling)) $('staff').value = selectedBilling;
  if (selectedReport === 'ALL' || staff.some(member => member.id === selectedReport)) $('reportStaff').value = selectedReport || 'ALL';
}

function renderServices() {
  const services = activeServices();
  $('serviceButtons').innerHTML = services.map(service => `<button class="service-btn" data-service-id="${safe(service.id)}">${safe(service.name)}<span>${money(service.rate)}</span></button>`).join('');
  $('itemSelect').innerHTML = services.map(service => `<option value="${safe(service.id)}">${safe(service.name)} - ${money(service.rate)}</option>`).join('');

  document.querySelectorAll('[data-service-id]').forEach(button => {
    button.onclick = () => {
      const service = services.find(item => item.id === button.dataset.serviceId);
      if (service) addItem(service);
    };
  });

  $('itemSelect').onchange = () => {
    const service = services.find(item => item.id === $('itemSelect').value);
    if (service) $('itemRate').value = service.rate;
  };

  if (services[0]) {
    $('itemSelect').value = services[0].id;
    $('itemRate').value = services[0].rate;
  }
}

function addItem(service) {
  items.push({
    serviceId: service.id,
    name: service.name,
    category: service.category || 'Other',
    qty: 1,
    rate: Math.max(0, Number(service.rate) || 0)
  });
  dirty = true;
  renderItems();
  renderInvoice();
}

function addSelectedItem() {
  const service = activeServices().find(item => item.id === $('itemSelect').value);
  if (!service) return;
  items.push({
    serviceId: service.id,
    name: service.name,
    category: service.category || 'Other',
    qty: Math.max(1, Number($('itemQty').value) || 1),
    rate: Math.max(0, Number($('itemRate').value) || 0)
  });
  dirty = true;
  renderItems();
  renderInvoice();
}

function renderItems() {
  $('itemsBody').innerHTML = items.length ? items.map((item, index) => `
    <tr>
      <td><input value="${safe(item.name)}" data-item-index="${index}" data-item-key="name"></td>
      <td><input type="number" min="1" value="${item.qty}" data-item-index="${index}" data-item-key="qty"></td>
      <td><input type="number" min="0" value="${item.rate}" data-item-index="${index}" data-item-key="rate"></td>
      <td>${money((Number(item.qty) || 0) * (Number(item.rate) || 0))}</td>
      <td><button class="btn danger small" data-remove-item="${index}">×</button></td>
    </tr>
  `).join('') : '<tr><td colspan="5" class="empty">No items added</td></tr>';

  document.querySelectorAll('#itemsBody input').forEach(input => {
    input.oninput = () => {
      const index = Number(input.dataset.itemIndex);
      const key = input.dataset.itemKey;
      items[index][key] = key === 'name' ? input.value : Math.max(0, Number(input.value) || 0);
      dirty = true;
      renderInvoice();
      if (key !== 'name') renderItems();
    };
  });

  document.querySelectorAll('[data-remove-item]').forEach(button => {
    button.onclick = () => {
      items.splice(Number(button.dataset.removeItem), 1);
      dirty = true;
      renderItems();
      renderInvoice();
    };
  });
}

function invoiceHtml(bill, shop) {
  shop = shop || bill.settingsSnapshot || settingsSnapshot();
  const rows = (bill.items || []).map(item => `
    <tr><td>${safe(item.name)}</td><td>${safe(item.qty)}</td><td>${money(item.rate)}</td><td>${money((Number(item.qty) || 0) * (Number(item.rate) || 0))}</td></tr>
  `).join('') || '<tr><td colspan="4">No items</td></tr>';
  const taxRow = shop.gstEnabled && Number(bill.tax) > 0 ? `<div class="sum-row"><span>GST ${bill.tax}%</span><b>${money(bill.taxAmount)}</b></div>` : '';
  const qr = shop.qr ? `<img class="qr-img" src="${shop.qr}" alt="Payment QR">` : '';

  return `
    <div class="invoice-logo"><img src="banner.svg" alt="Purple Signature Salon"></div>
    <div class="invoice-head">
      <div><h3>${safe(shop.businessName)}</h3><p>${safe(shop.tagline)}</p><p>${safe(shop.address || '')}</p><p>${safe(shop.phone || '')}</p></div>
      <div><p><b>Invoice</b></p><p>${safe(bill.invoiceNo)}</p><p>${safe(bill.billDate)}</p></div>
    </div>
    <p><b>Customer:</b> ${safe(bill.customer)} ${bill.mobile ? '· ' + safe(bill.mobile) : ''}</p>
    <p><b>Staff:</b> ${safe(bill.staff || '-')} &nbsp; <b>Payment:</b> ${safe(paymentLabel(bill.payment))}</p>
    <table><thead><tr><th>Item</th><th>Qty</th><th>Rate</th><th>Total</th></tr></thead><tbody>${rows}</tbody></table>
    <div class="summary">
      <div class="sum-row"><span>Subtotal</span><b>${money(bill.subtotal)}</b></div>
      <div class="sum-row"><span>Discount</span><b>${money(bill.discount)}</b></div>
      ${taxRow}
      <div class="sum-row grand"><span>Grand Total</span><strong>${money(bill.grand)}</strong></div>
    </div>
    <p><b>Notes:</b> ${safe(bill.notes || '-')}</p>
    ${qr}
    <p class="muted" style="text-align:center">Thank you. Visit again.</p>
  `;
}

function renderInvoice() {
  $('invoicePreview').innerHTML = invoiceHtml(billData(), settingsSnapshot());
}

async function resetBill(ask = true) {
  if (ask && dirty && !confirm('Clear current unsaved bill?')) return;
  const previous = $('invoiceNo').value;
  if (previous) reservedInvoiceNumbers.delete(previous);
  items = [];
  editingInvoiceNo = null;
  editingCreatedAt = null;
  $('billDate').value = today();
  $('customer').value = '';
  $('mobile').value = '';
  $('staff').value = '';
  $('payment').value = 'CASH';
  $('discount').value = 0;
  $('notes').value = '';
  $('itemQty').value = 1;
  await assignNewInvoiceNumber();
  dirty = false;
  renderItems();
  renderInvoice();
}

async function saveBill() {
  if (!validateCurrentBill(false)) return;
  const button = $('saveBillBtn');
  if (button.dataset.saving === '1') return;

  try {
    button.dataset.saving = '1';
    button.disabled = true;
    button.textContent = 'Saving...';
    calculateTotals(true);

    let bill = billData();

    if (editingInvoiceNo) {
      bill.invoiceNo = editingInvoiceNo;
      await putBill(bill);
    } else {
      let saved = false;
      for (let attempt = 0; attempt < 5 && !saved; attempt++) {
        try {
          await addBill(bill);
          saved = true;
        } catch (error) {
          if (error && error.name === 'ConstraintError') {
            bill.invoiceNo = await nextInvoiceNo();
            $('invoiceNo').value = bill.invoiceNo;
          } else {
            throw error;
          }
        }
      }
      if (!saved) throw new Error('Unable to reserve a unique invoice number');
    }

    const verified = await getBill(bill.invoiceNo);
    if (!verified) throw new Error('Saved bill verification failed');

    dirty = false;
    await renderHistory();
    await renderTodaySummary();
    toast('Bill ' + bill.invoiceNo + ' saved successfully', 'success');

    setTimeout(async () => {
      if (confirm('Bill saved successfully. Open a new bill?')) await resetBill(false);
    }, 150);
  } catch (error) {
    console.error('Bill save failed', error);
    toast('Bill save failed: ' + (error.message || 'Unknown database error'), 'error');
  } finally {
    button.dataset.saving = '0';
    button.disabled = false;
    button.textContent = 'Save Bill';
  }
}

async function renderHistory() {
  let bills = await allBills();
  const query = $('billSearch').value.trim().toLowerCase();
  const selectedDate = $('billSearchDate').value;
  bills = bills.filter(bill => bill.status !== 'CANCELLED').sort((a, b) => String(b.savedAt || b.createdAt || '').localeCompare(String(a.savedAt || a.createdAt || '')));
  if (query) bills = bills.filter(bill => [bill.invoiceNo, bill.customer, bill.mobile, bill.staff].join(' ').toLowerCase().includes(query));
  if (selectedDate) bills = bills.filter(bill => bill.billDate === selectedDate);

  $('billCount').textContent = bills.length + (bills.length === 1 ? ' bill' : ' bills');
  $('billHistory').innerHTML = bills.slice(0, 300).map(bill => `
    <div class="history-item">
      <div class="history-top">
        <div><b>${safe(bill.invoiceNo)}</b><div class="muted">${safe(bill.customer)} · ${safe(bill.mobile || 'No mobile')}</div><div class="muted">${safe(bill.billDate)} · ${safe(bill.staff || '-')} · ${safe(paymentLabel(bill.payment))}</div></div>
        <strong>${money(bill.grand)}</strong>
      </div>
      <div class="history-actions">
        <button class="btn light small" data-view="${safe(bill.invoiceNo)}">View</button>
        <button class="btn dark small" data-print="${safe(bill.invoiceNo)}">Print</button>
        <button class="btn light small" data-share="${safe(bill.invoiceNo)}">Share</button>
        <button class="btn primary small" data-edit="${safe(bill.invoiceNo)}">Edit</button>
        <button class="btn danger small" data-delete="${safe(bill.invoiceNo)}">Delete</button>
      </div>
    </div>
  `).join('') || '<div class="empty">No saved bills</div>';

  document.querySelectorAll('[data-view]').forEach(button => button.onclick = () => openBill(button.dataset.view));
  document.querySelectorAll('[data-print]').forEach(button => button.onclick = () => withBill(button.dataset.print, sendBillPdf));
  document.querySelectorAll('[data-share]').forEach(button => button.onclick = () => withBill(button.dataset.share, sendBillShare));
  document.querySelectorAll('[data-edit]').forEach(button => button.onclick = () => editBill(button.dataset.edit));
  document.querySelectorAll('[data-delete]').forEach(button => button.onclick = () => removeBill(button.dataset.delete));
}

async function withBill(invoiceNumber, callback) {
  const bill = await getBill(invoiceNumber);
  if (bill) callback(bill);
}

async function openBill(invoiceNumber) {
  const bill = await getBill(invoiceNumber);
  if (!bill) return;
  viewedBill = bill;
  $('savedBillView').innerHTML = invoiceHtml(bill, bill.settingsSnapshot);
  $('billModal').classList.remove('hidden');
}

function closeBillModal() {
  $('billModal').classList.add('hidden');
  viewedBill = null;
}

async function editBill(invoiceNumber) {
  const bill = await getBill(invoiceNumber);
  if (!bill) return;
  editingInvoiceNo = bill.invoiceNo;
  editingCreatedAt = bill.createdAt || bill.savedAt || new Date().toISOString();
  items = clone(bill.items || []);
  $('invoiceNo').value = bill.invoiceNo;
  $('billDate').value = bill.billDate || today();
  $('customer').value = bill.customer || '';
  $('mobile').value = normalizeIndianMobile(bill.mobile) || bill.mobile || '';
  $('staff').value = bill.staffId || '';
  if (!$('staff').value) {
    const matching = settings.staff.find(member => member.name === bill.staff);
    if (matching) $('staff').value = matching.id;
  }
  $('payment').value = normalizePayment(bill.payment);
  $('discount').value = Math.max(0, Number(bill.discount) || 0);
  $('notes').value = bill.notes || '';
  dirty = false;
  renderItems();
  renderInvoice();
  closeBillModal();
  await showPage('billing');
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

async function removeBill(invoiceNumber) {
  if (!confirm('Delete bill ' + invoiceNumber + '?')) return;
  await deleteBillDb(invoiceNumber);
  reservedInvoiceNumbers.delete(invoiceNumber);
  await renderHistory();
  await renderTodaySummary();
  toast('Bill deleted', 'success');
}

function nativePayload(bill) {
  return JSON.stringify({ bill, settings: bill.settingsSnapshot || settingsSnapshot() });
}

function sendBillPdf(bill) {
  if (window.NativePdf && typeof window.NativePdf.savePdf === 'function') {
    window.NativePdf.savePdf(nativePayload(bill));
  } else {
    window.print();
  }
}

function sendBillShare(bill) {
  const normalized = normalizeIndianMobile(bill.mobile);
  if (!normalized) {
    toast('A valid customer mobile number is required for WhatsApp sharing', 'error');
    return;
  }
  bill.mobile = normalized;
  if (window.NativeShare && typeof window.NativeShare.shareBill === 'function') {
    window.NativeShare.shareBill(nativePayload(bill));
  } else {
    toast('WhatsApp image sharing works in the Android APK', 'error');
  }
}

async function renderTodaySummary() {
  const bills = (await allBills()).filter(bill => bill.billDate === today() && bill.status !== 'CANCELLED');
  let services = 0;
  let earnings = 0;
  for (const bill of bills) {
    earnings += Number(bill.grand) || 0;
    for (const item of bill.items || []) services += Number(item.qty) || 0;
  }
  $('todayServiceTotal').textContent = Number.isInteger(services) ? String(services) : services.toFixed(2);
  $('todayEarningsTotal').textContent = money(earnings);
  $('todaySummaryDate').textContent = new Date(today() + 'T00:00:00').toLocaleDateString('en-IN', { dateStyle: 'medium' });
}

function weekKey(dateString) {
  const date = new Date(dateString + 'T00:00:00');
  const day = (date.getDay() + 6) % 7;
  date.setDate(date.getDate() - day);
  const start = date.toISOString().slice(0, 10);
  date.setDate(date.getDate() + 6);
  return start + ' to ' + date.toISOString().slice(0, 10);
}

async function setRange(type) {
  const now = new Date();
  const to = today();
  const from = new Date(now);
  if (type === 'week') {
    const day = (now.getDay() + 6) % 7;
    from.setDate(now.getDate() - day);
  } else if (type === 'month') {
    from.setDate(1);
  }
  $('reportFrom').value = type === 'today' ? to : new Date(from.getTime() - from.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
  $('reportTo').value = to;
  await generateReport();
}

async function generateReport() {
  let bills = (await allBills()).filter(bill => bill.status !== 'CANCELLED');
  const from = $('reportFrom').value;
  const to = $('reportTo').value;
  const staffFilter = $('reportStaff').value;
  const paymentFilter = $('reportPayment').value;

  if (from) bills = bills.filter(bill => bill.billDate >= from);
  if (to) bills = bills.filter(bill => bill.billDate <= to);
  if (staffFilter !== 'ALL') bills = bills.filter(bill => (bill.staffId || bill.staff) === staffFilter);
  if (paymentFilter !== 'ALL') bills = bills.filter(bill => normalizePayment(bill.payment) === paymentFilter);

  const summary = { bills: bills.length, revenue: 0, cash: 0, upi: 0, card: 0, credit: 0, services: 0, discount: 0, cashBills: 0, upiBills: 0, cardBills: 0, creditBills: 0 };
  const staffMap = {};
  const serviceMap = {};
  const dailyMap = {};
  const weeklyMap = {};

  for (const bill of bills) {
    const total = Number(bill.grand) || 0;
    const payment = normalizePayment(bill.payment);
    const paymentKey = payment.toLowerCase();
    summary.revenue += total;
    summary.discount += Number(bill.discount) || 0;
    summary[paymentKey] += total;
    summary[paymentKey + 'Bills'] += 1;

    const staffId = String(bill.staffId || bill.staff || 'UNKNOWN');
    const staffName = String(bill.staff || settings.staff.find(member => member.id === staffId)?.name || staffId);
    if (!staffMap[staffId]) staffMap[staffId] = { staffId, staff: staffName, bills: 0, services: 0, revenue: 0, cash: 0, upi: 0, card: 0, credit: 0, serviceCounts: {} };
    const staffRow = staffMap[staffId];
    staffRow.bills += 1;
    staffRow.revenue += total;
    staffRow[paymentKey] += total;

    if (!dailyMap[bill.billDate]) dailyMap[bill.billDate] = { date: bill.billDate, bills: 0, services: 0, cash: 0, upi: 0, card: 0, credit: 0, total: 0 };
    const dailyRow = dailyMap[bill.billDate];
    dailyRow.bills += 1;
    dailyRow.total += total;
    dailyRow[paymentKey] += total;

    const week = weekKey(bill.billDate);
    if (!weeklyMap[week]) weeklyMap[week] = { week, bills: 0, services: 0, cash: 0, upi: 0, card: 0, credit: 0, total: 0 };
    const weeklyRow = weeklyMap[week];
    weeklyRow.bills += 1;
    weeklyRow.total += total;
    weeklyRow[paymentKey] += total;

    for (const item of bill.items || []) {
      const quantity = Number(item.qty) || 0;
      const revenue = quantity * (Number(item.rate) || 0);
      summary.services += quantity;
      staffRow.services += quantity;
      staffRow.serviceCounts[item.name] = (staffRow.serviceCounts[item.name] || 0) + quantity;
      dailyRow.services += quantity;
      weeklyRow.services += quantity;

      const serviceKey = item.serviceId || item.name;
      if (!serviceMap[serviceKey]) serviceMap[serviceKey] = { service: item.name, count: 0, qty: 0, revenue: 0, staffCounts: {} };
      const serviceRow = serviceMap[serviceKey];
      serviceRow.count += 1;
      serviceRow.qty += quantity;
      serviceRow.revenue += revenue;
      serviceRow.staffCounts[staffName] = (serviceRow.staffCounts[staffName] || 0) + quantity;
    }
  }

  summary.average = summary.bills ? summary.revenue / summary.bills : 0;
  const staffRows = Object.values(staffMap).map(row => ({
    ...row,
    topService: Object.entries(row.serviceCounts).sort((a, b) => b[1] - a[1])[0]?.[0] || '-'
  })).sort((a, b) => b.revenue - a.revenue);

  const serviceRows = Object.values(serviceMap).map(row => ({
    ...row,
    staffBreakdown: Object.entries(row.staffCounts).sort((a, b) => b[1] - a[1]).map(([name, quantity]) => name + ': ' + quantity).join(', ') || '-'
  })).sort((a, b) => b.revenue - a.revenue);

  currentReport = {
    title: 'Purple Signature Salon Report',
    from,
    to,
    staff: staffFilter,
    payment: paymentFilter,
    summary,
    staffRows,
    serviceRows,
    dailyRows: Object.values(dailyMap).sort((a, b) => a.date.localeCompare(b.date)),
    weeklyRows: Object.values(weeklyMap).sort((a, b) => a.week.localeCompare(b.week)),
    generatedAt: new Date().toISOString(),
    settings: settingsSnapshot()
  };

  renderReport();
}

function renderReport() {
  if (!currentReport) return;
  const report = currentReport;
  const cards = [
    ['Total Bills', report.summary.bills],
    ['Total Revenue', money(report.summary.revenue)],
    ['Cash', money(report.summary.cash)],
    ['UPI / GPay', money(report.summary.upi)],
    ['Card', money(report.summary.card)],
    ['Credit', money(report.summary.credit)],
    ['Services', report.summary.services],
    ['Average Bill', money(report.summary.average)]
  ];

  $('summaryCards').innerHTML = cards.map(card => `<div class="summary-card"><span>${card[0]}</span><strong>${card[1]}</strong></div>`).join('');

  $('staffReportBody').innerHTML = report.staffRows.map(row => `
    <tr><td>${safe(row.staff)}</td><td>${row.bills}</td><td>${row.services}</td><td>${money(row.revenue)}</td><td>${money(row.cash)}</td><td>${money(row.upi)}</td><td>${safe(row.topService)}</td></tr>
  `).join('') || '<tr><td colspan="7">No data</td></tr>';

  $('paymentReport').innerHTML = '<div class="payment-cards">' + [
    ['Cash', report.summary.cash], ['UPI / GPay', report.summary.upi], ['Card', report.summary.card], ['Credit', report.summary.credit]
  ].map(entry => `<div class="payment-card"><span>${entry[0]}</span><strong>${money(entry[1])}</strong></div>`).join('') + '</div>';

  $('billCountReport').innerHTML = '<div class="payment-cards">' + [
    ['Cash Bills', report.summary.cashBills], ['UPI Bills', report.summary.upiBills], ['Card Bills', report.summary.cardBills], ['Credit Bills', report.summary.creditBills]
  ].map(entry => `<div class="payment-card"><span>${entry[0]}</span><strong>${entry[1]}</strong></div>`).join('') + '</div>';

  $('serviceReportBody').innerHTML = report.serviceRows.map(row => `
    <tr><td>${safe(row.service)}</td><td>${row.count}</td><td>${row.qty}</td><td>${money(row.revenue)}</td><td><div class="staff-breakdown">${Object.entries(row.staffCounts).map(([name, quantity]) => `<span class="staff-chip">${safe(name)}: ${quantity}</span>`).join('') || '-'}</div></td></tr>
  `).join('') || '<tr><td colspan="5">No data</td></tr>';

  $('dailyReportBody').innerHTML = report.dailyRows.map(row => `
    <tr><td>${row.date}</td><td>${row.bills}</td><td>${row.services}</td><td>${money(row.cash)}</td><td>${money(row.upi)}</td><td>${money(row.card)}</td><td>${money(row.credit)}</td><td>${money(row.total)}</td></tr>
  `).join('') || '<tr><td colspan="8">No data</td></tr>';

  $('weeklyReportBody').innerHTML = report.weeklyRows.map(row => `
    <tr><td>${row.week}</td><td>${row.bills}</td><td>${row.services}</td><td>${money(row.cash)}</td><td>${money(row.upi)}</td><td>${money(row.card)}</td><td>${money(row.credit)}</td><td>${money(row.total)}</td></tr>
  `).join('') || '<tr><td colspan="8">No data</td></tr>';
}

function exportReport() {
  if (!currentReport) return;
  if (window.NativeReport && typeof window.NativeReport.saveReport === 'function') {
    window.NativeReport.saveReport(JSON.stringify(currentReport));
  } else {
    toast('Report PDF export works in the Android APK', 'error');
  }
}

function openSettings() {
  sessionStorage.setItem('pssSettingsUnlocked', '1');
  $('settingsLock').classList.add('hidden');
  $('settingsPanel').classList.remove('hidden');
  fillSettings();
}

function fillSettings() {
  $('setBusiness').value = settings.businessName;
  $('setTagline').value = settings.tagline;
  $('setPhone').value = settings.phone;
  $('setAddress').value = settings.address;
  $('gstEnabled').checked = Boolean(settings.gstEnabled);
  $('gstPercent').value = settings.gstPercent || 0;
  $('qrPreview').innerHTML = settings.qr ? `<img class="qr-img" src="${settings.qr}" alt="QR preview">` : '';
  renderStaffSettings();
  renderServiceSettings();
}

function renderStaffSettings() {
  $('staffSettings').innerHTML = settings.staff.map((member, index) => `
    <div class="staff-setting">
      <input type="text" value="${safe(member.name)}" data-staff-name="${index}">
      <label class="switch-row"><input type="checkbox" data-staff-active="${index}" ${member.active !== false ? 'checked' : ''}><span>Active</span></label>
      <button class="btn danger small remove-staff" data-remove-staff="${index}">Remove</button>
    </div>
  `).join('');

  document.querySelectorAll('[data-remove-staff]').forEach(button => {
    button.onclick = () => {
      const index = Number(button.dataset.removeStaff);
      if (settings.staff.length <= 1) {
        toast('At least one staff member is required', 'error');
        return;
      }
      settings.staff[index].active = false;
      renderStaffSettings();
    };
  });
}

function renderServiceSettings() {
  $('serviceSettingsBody').innerHTML = settings.services.map((service, index) => `
    <tr>
      <td><input value="${safe(service.name)}" data-service-name="${index}"></td>
      <td><input value="${safe(service.category || 'Other')}" data-service-category="${index}"></td>
      <td><input type="number" min="0" value="${service.rate}" data-service-rate="${index}"></td>
      <td><input type="checkbox" data-service-active="${index}" ${service.active !== false ? 'checked' : ''}></td>
      <td><button class="btn danger small" data-remove-service="${index}">×</button></td>
    </tr>
  `).join('');

  document.querySelectorAll('[data-remove-service]').forEach(button => {
    button.onclick = () => {
      settings.services.splice(Number(button.dataset.removeService), 1);
      renderServiceSettings();
    };
  });
}

function readSettingsForm() {
  settings.businessName = $('setBusiness').value.trim() || DEFAULT_SETTINGS.businessName;
  settings.tagline = $('setTagline').value.trim() || DEFAULT_SETTINGS.tagline;
  settings.phone = $('setPhone').value.trim();
  settings.address = $('setAddress').value.trim();
  settings.gstEnabled = $('gstEnabled').checked;
  settings.gstPercent = Math.min(100, Math.max(0, Number($('gstPercent').value) || 0));

  document.querySelectorAll('[data-staff-name]').forEach(input => {
    const member = settings.staff[Number(input.dataset.staffName)];
    member.name = input.value.trim().toUpperCase() || member.id;
  });
  document.querySelectorAll('[data-staff-active]').forEach(input => settings.staff[Number(input.dataset.staffActive)].active = input.checked);
  document.querySelectorAll('[data-service-name]').forEach(input => settings.services[Number(input.dataset.serviceName)].name = input.value.trim() || 'Service');
  document.querySelectorAll('[data-service-category]').forEach(input => settings.services[Number(input.dataset.serviceCategory)].category = input.value.trim() || 'Other');
  document.querySelectorAll('[data-service-rate]').forEach(input => settings.services[Number(input.dataset.serviceRate)].rate = Math.max(0, Number(input.value) || 0));
  document.querySelectorAll('[data-service-active]').forEach(input => settings.services[Number(input.dataset.serviceActive)].active = input.checked);
}

function saveSettings() {
  readSettingsForm();
  saveSettingsLocal();
  renderStaffOptions();
  renderServices();
  renderInvoice();
  toast('Settings saved', 'success');
}

function sha256(ascii) {
  function rightRotate(value, amount) { return (value >>> amount) | (value << (32 - amount)); }
  const mathPow = Math.pow;
  const maxWord = mathPow(2, 32);
  let result = '';
  const words = [];
  const asciiBitLength = ascii.length * 8;
  const hash = [];
  const constants = [];
  const composite = {};
  let primeCounter = 0;

  for (let candidate = 2; primeCounter < 64; candidate++) {
    if (!composite[candidate]) {
      for (let multiple = candidate * candidate; multiple < 313; multiple += candidate) composite[multiple] = true;
      hash[primeCounter] = (mathPow(candidate, 0.5) * maxWord) | 0;
      constants[primeCounter++] = (mathPow(candidate, 1 / 3) * maxWord) | 0;
    }
  }

  ascii += '\x80';
  while (ascii.length % 64 - 56) ascii += '\x00';
  for (let index = 0; index < ascii.length; index++) {
    const code = ascii.charCodeAt(index);
    if (code >> 8) throw new Error('PIN contains invalid characters');
    words[index >> 2] |= code << ((3 - index) % 4) * 8;
  }
  words[words.length] = (asciiBitLength / maxWord) | 0;
  words[words.length] = asciiBitLength;

  for (let block = 0; block < words.length;) {
    const chunk = words.slice(block, block += 16);
    const oldHash = hash.slice(0);
    let working = hash.slice(0, 8);

    for (let index = 0; index < 64; index++) {
      const w15 = chunk[index - 15];
      const w2 = chunk[index - 2];
      const a = working[0];
      const e = working[4];
      const temp1 = working[7] + (rightRotate(e, 6) ^ rightRotate(e, 11) ^ rightRotate(e, 25)) + ((e & working[5]) ^ ((~e) & working[6])) + constants[index] + (chunk[index] = index < 16 ? chunk[index] : (chunk[index - 16] + (rightRotate(w15, 7) ^ rightRotate(w15, 18) ^ (w15 >>> 3)) + chunk[index - 7] + (rightRotate(w2, 17) ^ rightRotate(w2, 19) ^ (w2 >>> 10))) | 0);
      const temp2 = (rightRotate(a, 2) ^ rightRotate(a, 13) ^ rightRotate(a, 22)) + ((a & working[1]) ^ (a & working[2]) ^ (working[1] & working[2]));
      working = [(temp1 + temp2) | 0].concat(working);
      working[4] = (working[4] + temp1) | 0;
      working.pop();
    }

    for (let index = 0; index < 8; index++) hash[index] = (working[index] + oldHash[index]) | 0;
  }

  for (let index = 0; index < 8; index++) {
    for (let byte = 3; byte + 1; byte--) {
      const value = (hash[index] >> byte * 8) & 255;
      result += (value < 16 ? '0' : '') + value.toString(16);
    }
  }
  return result;
}

function hashAdminPin(pin) {
  return sha256('PSS|' + String(pin));
}

function unlockSettings() {
  const pin = $('settingsPassword').value;
  if (hashAdminPin(pin) !== settings.adminPinHash) {
    toast('Wrong admin PIN', 'error');
    markError($('settingsPassword'));
    return;
  }
  $('settingsPassword').value = '';
  openSettings();
}

function changeAdminPin() {
  const currentPin = $('currentAdminPin').value;
  const newPin = $('newAdminPin').value;
  const confirmation = $('confirmAdminPin').value;

  if (hashAdminPin(currentPin) !== settings.adminPinHash) {
    toast('Current admin PIN is incorrect', 'error');
    return;
  }
  if (!/^\d{4,8}$/.test(newPin)) {
    toast('New PIN must contain 4 to 8 digits', 'error');
    return;
  }
  if (newPin !== confirmation) {
    toast('New PIN confirmation does not match', 'error');
    return;
  }

  settings.adminPinHash = hashAdminPin(newPin);
  saveSettingsLocal();
  $('currentAdminPin').value = '';
  $('newAdminPin').value = '';
  $('confirmAdminPin').value = '';
  toast('Admin PIN changed', 'success');
}

async function exportBackup() {
  const bills = await allBills();
  const blob = new Blob([JSON.stringify({ version: 3, settings, bills }, null, 2)], { type: 'application/json' });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = 'purple-signature-backup-' + today() + '.json';
  link.click();
  URL.revokeObjectURL(link.href);
}

function importBackupFile(file) {
  const reader = new FileReader();
  reader.onload = async () => {
    try {
      const data = JSON.parse(reader.result);
      if (data.settings) {
        settings = normalizeSettings(data.settings);
        saveSettingsLocal();
      }
      for (const bill of data.bills || []) await putBill(bill);
      fillSettings();
      renderStaffOptions();
      renderServices();
      await renderHistory();
      await renderTodaySummary();
      toast('Backup imported', 'success');
    } catch (error) {
      toast('Invalid backup file', 'error');
    }
  };
  reader.readAsText(file);
}

function bindEvents() {
  $('addItemBtn').onclick = addSelectedItem;
  $('saveBillBtn').onclick = saveBill;
  $('newBillBtn').onclick = () => resetBill(true);
  $('printBillBtn').onclick = () => {
    if (!validateCurrentBill(false)) return;
    sendBillPdf(billData());
  };
  $('shareBillBtn').onclick = () => {
    if (!validateCurrentBill(true)) return;
    sendBillShare(billData());
  };

  $('billSearch').oninput = renderHistory;
  $('billSearchDate').oninput = renderHistory;
  $('closeBillModal').onclick = closeBillModal;
  $('modalPrintBtn').onclick = () => viewedBill && sendBillPdf(viewedBill);
  $('modalShareBtn').onclick = () => viewedBill && sendBillShare(viewedBill);
  $('modalEditBtn').onclick = () => viewedBill && editBill(viewedBill.invoiceNo);

  $('generateReportBtn').onclick = generateReport;
  $('exportReportBtn').onclick = exportReport;
  document.querySelectorAll('[data-range]').forEach(button => button.onclick = () => setRange(button.dataset.range));

  $('unlockSettingsBtn').onclick = unlockSettings;
  $('settingsPassword').onkeydown = event => { if (event.key === 'Enter') unlockSettings(); };
  $('lockSettingsBtn').onclick = () => {
    sessionStorage.removeItem('pssSettingsUnlocked');
    $('settingsPanel').classList.add('hidden');
    $('settingsLock').classList.remove('hidden');
  };
  $('saveSettingsBtn').onclick = saveSettings;
  $('changeAdminPinBtn').onclick = changeAdminPin;
  $('addStaffBtn').onclick = () => {
    const id = 'STAFF-' + Date.now();
    settings.staff.push({ id, name: 'NEW STAFF', active: true });
    renderStaffSettings();
  };
  $('addServiceSettingBtn').onclick = () => {
    settings.services.push({ id: 'service-' + Date.now(), name: 'New Service', category: 'Other', rate: 0, active: true });
    renderServiceSettings();
  };
  $('setQr').onchange = event => {
    const file = event.target.files && event.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      settings.qr = reader.result;
      $('qrPreview').innerHTML = `<img class="qr-img" src="${settings.qr}" alt="QR preview">`;
    };
    reader.readAsDataURL(file);
  };
  $('exportBackupBtn').onclick = exportBackup;
  $('importBackup').onchange = event => event.target.files[0] && importBackupFile(event.target.files[0]);

  ['billDate', 'customer', 'staff', 'payment', 'notes'].forEach(id => {
    $(id).oninput = () => { dirty = true; renderInvoice(); };
  });

  $('mobile').oninput = () => {
    $('mobile').value = $('mobile').value.replace(/[^0-9+]/g, '').slice(0, 12);
    dirty = true;
    renderInvoice();
  };
  $('mobile').onblur = () => {
    const normalized = normalizeIndianMobile($('mobile').value);
    if (normalized) $('mobile').value = normalized;
    else if ($('mobile').value.trim()) toast('Enter a valid 10-digit Indian mobile number', 'error');
    renderInvoice();
  };

  $('discount').oninput = () => { dirty = true; renderInvoice(); };
  $('discount').onblur = () => {
    calculateTotals(true);
    renderInvoice();
  };
}

async function init() {
  try {
    await openDb();
    await migrateBills();
    renderNav();
    bindEvents();
    renderStaffOptions();
    renderServices();
    $('billDate').value = today();
    $('billingDateChip').textContent = new Date(today() + 'T00:00:00').toLocaleDateString('en-IN', { dateStyle: 'medium' });
    await assignNewInvoiceNumber();
    renderItems();
    renderInvoice();
    await renderHistory();
    await renderTodaySummary();
    await setRange('today');
    if (sessionStorage.getItem('pssSettingsUnlocked') === '1') openSettings();
  } catch (error) {
    console.error('App startup failed', error);
    document.body.innerHTML = `<div style="padding:24px;color:white;font-family:sans-serif"><h2>App failed to start</h2><p>${safe(error.message || error)}</p><p>Close the app completely and reopen it. If the message remains, install the latest APK.</p></div>`;
  }
}

init();
