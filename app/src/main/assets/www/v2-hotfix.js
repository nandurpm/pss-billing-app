(function(){
  'use strict';

  function injectFixStyles(){
    if(document.getElementById('v2HotfixStyles')) return;
    var style=document.createElement('style');
    style.id='v2HotfixStyles';
    style.textContent=`
      .brand{height:94px;overflow:hidden;background:#fff;border-radius:15px}
      .brand img{width:100%!important;height:100%!important;object-fit:cover!important;object-position:center!important;display:block;transform:scale(1.06)}
      .mobile-head{overflow:hidden}
      .mobile-head img{height:58px!important;width:100%!important;object-fit:cover!important;object-position:center!important;display:block;transform:scale(1.08)}
      .invoice-logo{overflow:hidden;border-radius:12px;background:#fff}
      .invoice-logo img{width:100%!important;max-width:none!important;height:112px!important;object-fit:cover!important;object-position:center!important;display:block;transform:scale(1.06)}
      .field-error{outline:3px solid #d92d20!important;outline-offset:2px}
      .toast-message{position:fixed;left:50%;bottom:calc(22px + env(safe-area-inset-bottom));transform:translateX(-50%) translateY(20px);z-index:9999;min-width:240px;max-width:90vw;background:#28132f;color:#fff;padding:13px 16px;border-radius:14px;box-shadow:0 18px 45px #0008;font-weight:800;text-align:center;opacity:0;pointer-events:none;transition:.2s}
      .toast-message.show{opacity:1;transform:translateX(-50%) translateY(0)}
      .toast-message.error{background:#b42318}
      .toast-message.success{background:#16803d}
      @media(max-width:760px){
        .brand{height:90px}
        .action-bar{position:static!important;bottom:auto!important;margin-top:14px!important}
        .service-grid{margin-bottom:10px}
        .card{overflow:visible}
      }
    `;
    document.head.appendChild(style);
  }

  function toast(message,type){
    var el=document.getElementById('appToast');
    if(!el){
      el=document.createElement('div');
      el.id='appToast';
      el.className='toast-message';
      document.body.appendChild(el);
    }
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

  async function saveBillFixed(){
    var button=document.getElementById('saveBillBtn');
    if(button && button.dataset.saving==='1') return;

    var staffElement=document.getElementById('staff');
    if(!staffElement || !staffElement.value){
      toast('Select staff: ABITHA or NIVEDA','error');
      markError(staffElement);
      return;
    }

    if(typeof items==='undefined' || !Array.isArray(items) || items.length===0){
      toast('Add at least one service before saving','error');
      var serviceArea=document.getElementById('serviceButtons');
      if(serviceArea) serviceArea.scrollIntoView({behavior:'smooth',block:'center'});
      return;
    }

    try{
      if(button){
        button.dataset.saving='1';
        button.disabled=true;
        button.textContent='Saving...';
      }

      var bill=billData();
      if(!bill.invoiceNo){
        bill.invoiceNo=invoiceNo();
        document.getElementById('invoiceNo').value=bill.invoiceNo;
      }
      bill.status='SAVED';
      bill.savedAt=new Date().toISOString();
      await putBill(bill);

      var savedBills=await allBills();
      var verified=savedBills.some(function(row){return row.invoiceNo===bill.invoiceNo;});
      if(!verified) throw new Error('Saved bill verification failed');

      dirty=false;
      await renderHistory();
      toast('Bill '+bill.invoiceNo+' saved successfully','success');

      setTimeout(function(){
        if(confirm('Bill saved successfully. Open a new bill?')) resetBill(false);
      },150);
    }catch(error){
      console.error('Bill save failed',error);
      toast('Bill save failed: '+(error && error.message ? error.message : 'Unknown error'),'error');
    }finally{
      if(button){
        button.dataset.saving='0';
        button.disabled=false;
        button.textContent='Save Bill';
      }
    }
  }

  function install(){
    injectFixStyles();
    var button=document.getElementById('saveBillBtn');
    if(button){
      button.onclick=saveBillFixed;
      window.saveBill=saveBillFixed;
    }
  }

  window.addEventListener('load',function(){setTimeout(install,250);});
  var attempts=0;
  var timer=setInterval(function(){
    install();
    attempts++;
    if(attempts>20) clearInterval(timer);
  },400);
})();
