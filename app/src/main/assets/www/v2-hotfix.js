(function(){
  'use strict';

  function addStyles(){
    if(document.getElementById('v2HotfixStyles')) return;
    var style=document.createElement('style');
    style.id='v2HotfixStyles';
    style.textContent=`
      .brand{width:100%!important;height:auto!important;aspect-ratio:480/108!important;overflow:hidden!important;padding:0!important;background:transparent!important;border:1px solid #c29a45!important;border-radius:14px!important;box-shadow:0 12px 28px #0005!important}
      .brand img{width:100%!important;height:100%!important;max-height:none!important;object-fit:fill!important;object-position:center!important;display:block!important;transform:none!important;border:0!important;border-radius:13px!important}
      .mobile-head{grid-template-columns:auto minmax(0,1fr)!important;align-items:center!important;gap:10px!important;overflow:visible!important;background:linear-gradient(180deg,#260032,#1b0024)!important;padding-bottom:11px!important}
      .mobile-head #clock{display:none!important}
      .mobile-head img{width:100%!important;height:auto!important;max-height:none!important;aspect-ratio:480/108!important;object-fit:fill!important;object-position:center!important;display:block!important;transform:none!important;border:1px solid #c29a45!important;border-radius:14px!important;box-shadow:0 10px 26px #0006!important;background:transparent!important}
      .invoice-logo{width:100%!important;height:auto!important;aspect-ratio:480/108!important;overflow:hidden!important;padding:0!important;margin-bottom:12px!important;border:1px solid #c29a45!important;border-radius:12px!important;background:transparent!important}
      .invoice-logo img{width:100%!important;max-width:none!important;height:100%!important;object-fit:fill!important;object-position:center!important;display:block!important;transform:none!important;border:0!important;border-radius:11px!important}
      .field-error{outline:3px solid #d92d20!important;outline-offset:2px}
      .toast-message{position:fixed;left:50%;bottom:calc(22px + env(safe-area-inset-bottom));transform:translateX(-50%) translateY(20px);z-index:9999;min-width:240px;max-width:90vw;background:#28132f;color:#fff;padding:13px 16px;border-radius:14px;box-shadow:0 18px 45px #0008;font-weight:800;text-align:center;opacity:0;pointer-events:none;transition:.2s}
      .toast-message.show{opacity:1;transform:translateX(-50%) translateY(0)}
      .toast-message.error{background:#b42318}.toast-message.success{background:#16803d}
      .today-summary-card{margin-top:16px!important}.today-summary-head{display:flex;justify-content:space-between;align-items:center;gap:10px;margin-bottom:12px}.today-summary-head h2{margin:0;font-size:18px}.today-summary-head span{font-size:12px;color:#7b687f}
      .today-summary-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.today-summary-box{background:linear-gradient(135deg,#faf3fc,#fff);border:1px solid #eadfed;border-radius:16px;padding:15px}.today-summary-box span{display:block;font-size:12px;color:#7b687f;font-weight:800}.today-summary-box strong{display:block;margin-top:7px;font-size:25px;color:#701e82}
      @media(max-width:760px){.action-bar{position:static!important;bottom:auto!important;margin-top:14px!important}.service-grid{margin-bottom:10px}.card{overflow:visible}.today-summary-grid{grid-template-columns:1fr 1fr}}
      @media(max-width:420px){.today-summary-grid{grid-template-columns:1fr}}
    `;
    document.head.appendChild(style);
  }

  function toast(message,type){
    var el=document.getElementById('appToast');
    if(!el){el=document.createElement('div');el.id='appToast';document.body.appendChild(el);}
    el.textContent=message;
    el.className='toast-message '+(type||'')+' show';
    clearTimeout(el._timer);
    el._timer=setTimeout(function(){el.classList.remove('show');},2800);
  }

  function markError(element){
    if(!element) return;
    element.classList.add('field-error');
    element.scrollIntoView({behavior:'smooth',block:'center'});
    try{element.focus();}catch(e){}
    setTimeout(function(){element.classList.remove('field-error');},2500);
  }

  function nextInvoiceNumber(){
    var d=new Date();
    var base=String(d.getDate()).padStart(2,'0')+String(d.getMonth()+1).padStart(2,'0')+d.getFullYear()+'-'+String(d.getHours()).padStart(2,'0')+'_'+String(d.getMinutes()).padStart(2,'0');
    var state={};
    try{state=JSON.parse(localStorage.getItem('pssInvoiceSequence')||'{}');}catch(e){}
    var sequence=state.base===base?(Number(state.sequence)||0)+1:1;
    localStorage.setItem('pssInvoiceSequence',JSON.stringify({base:base,sequence:sequence}));
    return base+'-'+String(sequence).padStart(3,'0');
  }

  function installInvoiceGenerator(){
    try{invoiceNo=nextInvoiceNumber;}catch(e){}
    window.invoiceNo=nextInvoiceNumber;
    var field=document.getElementById('invoiceNo');
    if(field&&!field.dataset.uniqueInvoiceReady){
      if(!field.value||/^\d{8}-\d{2}_\d{2}$/.test(field.value)) field.value=nextInvoiceNumber();
      field.dataset.uniqueInvoiceReady='1';
    }
  }

  function applyNivethaName(){
    var changed=false;
    try{
      if(typeof settings!=='undefined'&&Array.isArray(settings.staff)){
        settings.staff.forEach(function(staff){
          var id=String(staff.id||'').toUpperCase();
          var name=String(staff.name||'').toUpperCase();
          if((id==='NIVEDA'||name==='NIVEDA')&&staff.name!=='NIVETHA'){
            staff.name='NIVETHA';
            changed=true;
          }
        });
        if(changed){
          if(typeof saveSettingsLocal==='function') saveSettingsLocal();
          if(typeof renderStaffOptions==='function') renderStaffOptions();
        }
      }
    }catch(e){console.warn('Staff name update failed',e);}

    document.querySelectorAll('#staff option,#reportStaff option').forEach(function(option){
      if(option.textContent.trim().toUpperCase()==='NIVEDA') option.textContent='NIVETHA';
    });
    var body=document.getElementById('serviceReportBody');
    var header=body&&body.closest('table')&&body.closest('table').querySelector('thead th:last-child');
    if(header&&header.textContent.trim().toUpperCase()==='NIVEDA') header.textContent='NIVETHA';
  }

  async function migrateSavedStaff(){
    if(localStorage.getItem('pssNivethaBillsMigrated')==='1') return;
    try{
      if(typeof allBills!=='function'||typeof putBill!=='function') return;
      var bills=await allBills();
      var changed=false;
      for(var i=0;i<bills.length;i++){
        if(String(bills[i].staff||'').toUpperCase()==='NIVEDA'){
          bills[i].staff='NIVETHA';
          await putBill(bills[i]);
          changed=true;
        }
      }
      localStorage.setItem('pssNivethaBillsMigrated','1');
      if(changed&&typeof renderHistory==='function') await renderHistory();
    }catch(e){console.warn('Saved staff migration pending',e);}
  }

  function ensureTodaySummary(){
    if(document.getElementById('todayBillingSummary')) return;
    var billingGrid=document.querySelector('#page-billing .billing-grid');
    if(!billingGrid) return;
    var section=document.createElement('section');
    section.className='card today-summary-card';
    section.id='todayBillingSummary';
    section.innerHTML='<div class="today-summary-head"><h2>Today\'s Billing Summary</h2><span id="todaySummaryDate"></span></div><div class="today-summary-grid"><div class="today-summary-box"><span>Total Services</span><strong id="todayServiceTotal">0</strong></div><div class="today-summary-box"><span>Total Earnings</span><strong id="todayEarningsTotal">₹0.00</strong></div></div>';
    billingGrid.insertAdjacentElement('afterend',section);
  }

  async function renderTodaySummary(){
    ensureTodaySummary();
    var serviceElement=document.getElementById('todayServiceTotal');
    var earningsElement=document.getElementById('todayEarningsTotal');
    if(!serviceElement||!earningsElement) return;
    try{
      if(typeof allBills!=='function') return;
      var dateValue=typeof today==='function'?today():new Date().toISOString().slice(0,10);
      var bills=(await allBills()).filter(function(b){return b.billDate===dateValue&&b.status!=='CANCELLED';});
      var services=0,earnings=0;
      bills.forEach(function(b){earnings+=Number(b.grand)||0;(b.items||[]).forEach(function(i){services+=Number(i.qty)||0;});});
      serviceElement.textContent=Math.abs(services-Math.round(services))<0.001?String(Math.round(services)):services.toFixed(2);
      earningsElement.textContent=typeof money==='function'?money(earnings):'₹'+earnings.toFixed(2);
      var dateElement=document.getElementById('todaySummaryDate');
      if(dateElement) dateElement.textContent=new Date(dateValue+'T00:00:00').toLocaleDateString('en-IN',{dateStyle:'medium'});
    }catch(e){console.warn('Today summary update pending',e);}
  }

  function wrapHistory(){
    if(window.__pssHistorySummaryWrapped) return;
    try{
      var original=window.renderHistory||renderHistory;
      if(typeof original!=='function') return;
      var wrapped=async function(){var result=await original.apply(this,arguments);await renderTodaySummary();applyNivethaName();return result;};
      window.renderHistory=wrapped;
      try{renderHistory=wrapped;}catch(e){}
      window.__pssHistorySummaryWrapped=true;
    }catch(e){}
  }

  async function saveBillFixed(){
    var button=document.getElementById('saveBillBtn');
    if(button&&button.dataset.saving==='1') return;
    var staffElement=document.getElementById('staff');
    if(!staffElement||!staffElement.value){toast('Select staff: ABITHA or NIVETHA','error');markError(staffElement);return;}
    if(typeof items==='undefined'||!Array.isArray(items)||items.length===0){toast('Add at least one service before saving','error');var services=document.getElementById('serviceButtons');if(services)services.scrollIntoView({behavior:'smooth',block:'center'});return;}

    try{
      if(button){button.dataset.saving='1';button.disabled=true;button.textContent='Saving...';}
      var bill=billData();
      if(!bill.invoiceNo){bill.invoiceNo=nextInvoiceNumber();document.getElementById('invoiceNo').value=bill.invoiceNo;}
      if(String(bill.staff||'').toUpperCase()==='NIVEDA') bill.staff='NIVETHA';
      bill.status='SAVED';
      bill.savedAt=new Date().toISOString();
      await putBill(bill);
      var verified=(await allBills()).some(function(row){return row.invoiceNo===bill.invoiceNo;});
      if(!verified) throw new Error('Saved bill verification failed');
      dirty=false;
      await renderHistory();
      await renderTodaySummary();
      toast('Bill '+bill.invoiceNo+' saved successfully','success');
      setTimeout(function(){if(confirm('Bill saved successfully. Open a new bill?')) resetBill(false);},150);
    }catch(error){
      console.error('Bill save failed',error);
      toast('Bill save failed: '+(error&&error.message?error.message:'Unknown error'),'error');
    }finally{
      if(button){button.dataset.saving='0';button.disabled=false;button.textContent='Save Bill';}
    }
  }

  function install(){
    addStyles();
    installInvoiceGenerator();
    applyNivethaName();
    ensureTodaySummary();
    wrapHistory();
    renderTodaySummary();
    migrateSavedStaff();
    var button=document.getElementById('saveBillBtn');
    if(button){button.onclick=saveBillFixed;window.saveBill=saveBillFixed;}
  }

  install();
  window.addEventListener('load',function(){setTimeout(install,200);});
  var attempts=0;
  var timer=setInterval(function(){install();attempts++;if(attempts>10)clearInterval(timer);},400);
})();
