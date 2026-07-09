function buildPrintableBillHtml() {
  const bill = billData();
  const rows = bill.items.map(item => `
    <tr>
      <td>${safe(item.name)}</td>
      <td class="num">${item.qty}</td>
      <td class="num">${money(item.rate)}</td>
      <td class="num">${money((Number(item.qty) || 0) * (Number(item.rate) || 0))}</td>
    </tr>
  `).join('') || '<tr><td colspan="4">No items added.</td></tr>';

  const qr = settings.qr ? `<div class="qrBox"><img src="${settings.qr}" alt="Payment QR"><p>Scan to pay</p></div>` : '';

  return `<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${safe(bill.invoiceNo)}</title>
<style>
  @page { size: A4; margin: 10mm; }
  * { box-sizing: border-box; }
  body { margin: 0; font-family: Arial, sans-serif; color: #24112f; background: white; }
  .bill { width: 100%; max-width: 760px; margin: 0 auto; border: 2px solid #7b248f; padding: 14px; }
  .logo { text-align: center; border-bottom: 2px solid #eaddee; padding-bottom: 10px; margin-bottom: 12px; }
  .logo img { width: 250px; max-width: 85%; border: 2px solid #b78a35; border-radius: 10px; }
  .head { display: flex; justify-content: space-between; gap: 12px; border-bottom: 1px dashed #bfa7c9; padding-bottom: 10px; margin-bottom: 10px; }
  h1 { margin: 0 0 4px; color: #7b248f; font-size: 22px; }
  p { margin: 4px 0; font-size: 13px; }
  table { width: 100%; border-collapse: collapse; margin-top: 10px; }
  th { background: #2a063c; color: white; text-align: left; padding: 8px; font-size: 12px; }
  td { border: 1px solid #eaddee; padding: 8px; font-size: 12px; }
  .num { text-align: right; }
  .summary { margin-top: 12px; margin-left: auto; width: 280px; }
  .sumrow { display: flex; justify-content: space-between; border: 1px solid #eaddee; padding: 8px; font-size: 13px; }
  .grand { background: #7b248f; color: white; font-weight: bold; font-size: 16px; }
  .qrBox { text-align: center; margin-top: 12px; }
  .qrBox img { max-width: 145px; max-height: 145px; border: 1px solid #eaddee; padding: 6px; }
  .footer { text-align: center; margin-top: 14px; color: #6f5877; font-size: 12px; }
</style>
</head>
<body>
  <div class="bill">
    <div class="logo"><img src="logo.svg" alt="Purple Signature Salon"></div>
    <div class="head">
      <div>
        <h1>${safe(settings.businessName)}</h1>
        <p>${safe(settings.tagline)}</p>
        <p>${safe(settings.address || '')}</p>
        <p>${safe(settings.phone || '')}</p>
      </div>
      <div class="num">
        <p><b>Invoice</b></p>
        <p>${safe(bill.invoiceNo)}</p>
        <p>${safe(bill.billDate)}</p>
        <p>${safe(settings.gst || '')}</p>
      </div>
    </div>
    <p><b>Customer:</b> ${safe(bill.customer)} ${bill.mobile ? ' · ' + safe(bill.mobile) : ''}</p>
    <table>
      <thead><tr><th>Item</th><th>Qty</th><th>Rate</th><th>Total</th></tr></thead>
      <tbody>${rows}</tbody>
    </table>
    <div class="summary">
      <div class="sumrow"><span>Subtotal</span><b>${money(bill.subtotal)}</b></div>
      <div class="sumrow"><span>Discount</span><b>${money(bill.discount)}</b></div>
      <div class="sumrow"><span>Tax ${bill.tax}%</span><b>${money(bill.taxAmount)}</b></div>
      <div class="sumrow grand"><span>Grand Total</span><b>${money(bill.grand)}</b></div>
    </div>
    <p><b>Payment:</b> ${safe(bill.payment)} &nbsp; <b>Staff:</b> ${safe(bill.staff || '-')}</p>
    <p><b>Notes:</b> ${safe(bill.notes || '-')}</p>
    ${qr}
    <div class="footer">Thank you. Visit again.</div>
  </div>
</body>
</html>`;
}

function printBill() {
  renderInvoice();
  const html = buildPrintableBillHtml();
  if (window.NativePrint && typeof window.NativePrint.printHtml === 'function') {
    window.NativePrint.printHtml(html);
  } else {
    const popup = window.open('', '_blank');
    if (popup) {
      popup.document.open();
      popup.document.write(html);
      popup.document.close();
      popup.focus();
      popup.print();
    } else {
      window.print();
    }
  }
}
