(function(){
  function replaceInvoiceBanner(){
    document.querySelectorAll('.invoiceLogo img').forEach(function(img){ img.src = 'banner.svg'; });
  }

  if (typeof window.renderInvoice === 'function') {
    var originalRenderInvoice = window.renderInvoice;
    window.renderInvoice = function(){
      originalRenderInvoice();
      replaceInvoiceBanner();
    };
  }

  window.printBill = function(){
    if (typeof renderInvoice === 'function') renderInvoice();
    var payload = JSON.stringify({
      bill: billData(),
      settings: settings
    });

    if (window.NativePdf && typeof window.NativePdf.savePdf === 'function') {
      window.NativePdf.savePdf(payload);
      return;
    }

    if (typeof buildPrintableBillHtml === 'function') {
      var html = buildPrintableBillHtml();
      var popup = window.open('', '_blank');
      if (popup) {
        popup.document.open();
        popup.document.write(html);
        popup.document.close();
        popup.focus();
        popup.print();
        return;
      }
    }

    window.print();
  };

  setTimeout(function(){
    if (typeof renderInvoice === 'function') renderInvoice();
  }, 200);
})();
