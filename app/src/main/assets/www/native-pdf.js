(function(){
  function invoiceNoNow(){
    var d = new Date();
    return String(d.getDate()).padStart(2,'0') + String(d.getMonth()+1).padStart(2,'0') + d.getFullYear() + '-' + String(d.getHours()).padStart(2,'0') + '_' + String(d.getMinutes()).padStart(2,'0');
  }

  window.makeInvoiceNo = invoiceNoNow;

  function settingsSnapshot(){
    return {
      businessName: settings.businessName || 'Purple Signature Salon',
      tagline: settings.tagline || 'Skin · Hair · Bridal · Nails',
      phone: settings.phone || '',
      address: settings.address || '',
      qr: settings.qr || '',
      gst: ''
    };
  }

  function cleanBillData(){
    var bill = billData();
    bill.tax = 0;
    bill.taxAmount = 0;
    bill.grand = Math.max(0, Number(bill.subtotal || 0) - Number(bill.discount || 0));
    bill.settingsSnapshot = settingsSnapshot();
    return bill;
  }

  function money2(n){ return money(Number(n) || 0); }

  function billHtml(bill, shop, forModal){
    shop = shop || settingsSnapshot();
    var rows = (bill.items || []).map(function(item){
      var total = (Number(item.qty) || 0) * (Number(item.rate) || 0);
      return '<tr><td>' + safe(item.name) + '</td><td class="num">' + safe(item.qty) + '</td><td class="num">' + money2(item.rate) + '</td><td class="num">' + money2(total) + '</td></tr>';
    }).join('') || '<tr><td colspan="4" class="small">No items added.</td></tr>';
    var qr = shop.qr ? '<img class="qr" src="' + shop.qr + '" alt="Payment QR"><div class="footer-note">Scan to pay</div>' : '';
    return '<div class="invoiceLogo"><img src="banner.svg" alt="Purple Signature Salon"></div>' +
      '<div class="invoiceHead"><div><h3>' + safe(shop.businessName || 'Purple Signature Salon') + '</h3><p>' + safe(shop.tagline || 'Skin · Hair · Bridal · Nails') + '</p><p>' + safe(shop.address || '') + '</p><p>' + safe(shop.phone || '') + '</p></div>' +
      '<div class="num"><p><b>Invoice</b></p><p>' + safe(bill.invoiceNo || '') + '</p><p>' + safe(bill.billDate || '') + '</p></div></div>' +
      '<p><b>Customer:</b> ' + safe(bill.customer || 'Walk-in Customer') + (bill.mobile ? ' · ' + safe(bill.mobile) : '') + '</p>' +
      '<table><thead><tr><th>Item</th><th>Qty</th><th>Rate</th><th>Total</th></tr></thead><tbody>' + rows + '</tbody></table>' +
      '<div class="summary"><div class="sumrow"><span>Subtotal</span><b>' + money2(bill.subtotal) + '</b></div>' +
      '<div class="sumrow"><span>Discount</span><b>' + money2(bill.discount) + '</b></div>' +
      '<div class="sumrow grand"><span>Grand Total</span><strong>' + money2(bill.grand) + '</strong></div></div>' +
      '<p><b>Payment:</b> ' + safe(bill.payment || 'Cash') + ' &nbsp; <b>Staff:</b> ' + safe(bill.staff || '-') + '</p>' +
      '<p><b>Notes:</b> ' + safe(bill.notes || '-') + '</p>' + qr + '<div class="footer-note">Thank you. Visit again.</div>';
  }

  window.renderInvoice = function(){
    var bill = cleanBillData();
    $('invoicePreview').innerHTML = billHtml(bill, settingsSnapshot(), false);
  };

  function hideTaxAndGst(){
    var tax = $('tax');
    if (tax) {
      tax.value = 0;
      var field = tax.closest('.field');
      if (field) field.style.display = 'none';
    }
    var gst = $('setGst');
    if (gst) {
      gst.value = '';
      var gstField = gst.closest('.field');
      if (gstField) gstField.style.display = 'none';
    }
  }

  window.saveBill = async function(){
    var bill = cleanBillData();
    if (!bill.items.length) {
      alert('Add at least one service or product.');
      return;
    }
    await putBill(bill);
    dirty = false;
    await renderHistory();
    if (confirm('Bill saved. Open new bill?')) startNew(false);
  };

  window.startNew = function(ask){
    if (ask && dirty && !confirm('Clear current unsaved bill and open new bill?')) return;
    items = [];
    $('invoiceNo').value = invoiceNoNow();
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
  };

  function ensureBillModal(){
    if (document.getElementById('billViewModal')) return;
    var modal = document.createElement('div');
    modal.id = 'billViewModal';
    modal.className = 'billViewModal hidden';
    modal.innerHTML = '<div class="billViewCard"><div class="billViewTop"><h2>Saved Bill</h2><button class="btn danger small" onclick="closeSavedBill()">Close</button></div><div class="invoice" id="savedBillInvoice"></div><div class="actions"><button class="btn dark" onclick="printViewedBill()">Print / PDF</button><button class="btn ghost" onclick="shareViewedBill()">WhatsApp Share</button></div></div>';
    document.body.appendChild(modal);
  }

  window.closeSavedBill = function(){
    var modal = document.getElementById('billViewModal');
    if (modal) modal.classList.add('hidden');
  };

  var viewedBill = null;
  window.viewSavedBill = async function(id){
    ensureBillModal();
    var bills = await allBills();
    viewedBill = bills.find(function(b){ return b.invoiceNo === id; });
    if (!viewedBill) return;
    var shop = viewedBill.settingsSnapshot || settingsSnapshot();
    document.getElementById('savedBillInvoice').innerHTML = billHtml(viewedBill, shop, true);
    document.getElementById('billViewModal').classList.remove('hidden');
  };

  window.printViewedBill = function(){
    if (!viewedBill) return;
    sendPdf(viewedBill, viewedBill.settingsSnapshot || settingsSnapshot());
  };

  window.shareViewedBill = function(){
    if (!viewedBill) return;
    sendShare(viewedBill, viewedBill.settingsSnapshot || settingsSnapshot());
  };

  window.renderHistory = async function(){
    var query = $('searchBox').value.trim().toLowerCase();
    var date = $('searchDate').value;
    var bills = await allBills();
    bills.sort(function(a,b){ return String(b.savedAt || '').localeCompare(String(a.savedAt || '')); });
    if (query) bills = bills.filter(function(bill){ return [bill.invoiceNo, bill.customer, bill.mobile, bill.payment, bill.staff].join(' ').toLowerCase().includes(query); });
    if (date) bills = bills.filter(function(bill){ return bill.billDate === date; });
    $('billCount').textContent = bills.length + ' bill' + (bills.length === 1 ? '' : 's');
    var shown = bills.slice(0, 300);
    $('history').innerHTML = shown.length ? shown.map(function(bill){
      return '<div class="historyItem" onclick="viewSavedBill(\'' + safe(bill.invoiceNo) + '\')"><div class="historyTop"><div><b>' + safe(bill.invoiceNo) + '</b><div class="small">' + safe(bill.customer) + ' · ' + safe(bill.mobile || 'No mobile') + '</div><div class="small">' + safe(bill.billDate) + ' · ' + safe(bill.payment) + '</div></div><div class="num"><b>' + money2(bill.grand) + '</b></div></div><div class="actions history-actions"><button class="btn ghost small" onclick="event.stopPropagation();viewSavedBill(\'' + safe(bill.invoiceNo) + '\')">View Bill</button><button class="btn danger small" onclick="event.stopPropagation();deleteBill(\'' + safe(bill.invoiceNo) + '\')">Delete</button></div></div>';
    }).join('') + (bills.length > shown.length ? '<div class="small">Showing latest 300 results. Use search/date filter to narrow older bills.</div>' : '') : '<div class="small">No saved bills found.</div>';
  };

  function payloadFor(bill, shop){
    return JSON.stringify({ bill: bill, settings: shop || settingsSnapshot() });
  }

  function sendPdf(bill, shop){
    if (window.NativePdf && typeof window.NativePdf.savePdf === 'function') {
      window.NativePdf.savePdf(payloadFor(bill, shop));
    } else if (typeof buildPrintableBillHtml === 'function') {
      var popup = window.open('', '_blank');
      if (popup) {
        popup.document.open();
        popup.document.write(buildPrintableBillHtml());
        popup.document.close();
        popup.focus();
        popup.print();
      } else window.print();
    } else window.print();
  }

  function sendShare(bill, shop){
    if (window.NativeShare && typeof window.NativeShare.shareBill === 'function') {
      window.NativeShare.shareBill(payloadFor(bill, shop));
    } else {
      alert('Bill image sharing works only in the Android APK.');
    }
  }

  window.printBill = function(){
    if (typeof renderInvoice === 'function') renderInvoice();
    sendPdf(cleanBillData(), settingsSnapshot());
  };

  window.shareBill = function(){
    if (typeof renderInvoice === 'function') renderInvoice();
    sendShare(cleanBillData(), settingsSnapshot());
  };

  setTimeout(function(){
    hideTaxAndGst();
    if ($('invoiceNo') && $('invoiceNo').value.indexOf('PSS-') === 0) $('invoiceNo').value = invoiceNoNow();
    renderInvoice();
    renderHistory();
  }, 250);
})();
