(function(){
  function slug(text,index){
    var value=String(text||'service').toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'');
    return value||('service-'+index);
  }
  try{
    var raw=localStorage.getItem('pssV2Settings')||localStorage.getItem('pssSettings')||'{}';
    var current=JSON.parse(raw);
    if(!Array.isArray(current.staff)||!current.staff.length){
      current.staff=[{id:'ABITHA',name:'ABITHA',active:true},{id:'NIVEDA',name:'NIVEDA',active:true}];
    }else{
      current.staff=current.staff.map(function(s,i){
        var name=String((s&&s.name)||s||'').trim().toUpperCase()||(['ABITHA','NIVEDA'][i]||('STAFF '+(i+1)));
        return {id:String((s&&s.id)||name).toUpperCase(),name:name,active:!(s&&s.active===false)};
      });
    }
    if(Array.isArray(current.services)){
      current.services=current.services.map(function(s,i){
        return {id:String(s.id||slug(s.name,i)),name:String(s.name||('Service '+(i+1))),category:String(s.category||'Other'),rate:Number(s.rate)||0,active:s.active!==false};
      });
    }
    localStorage.setItem('pssV2Settings',JSON.stringify(current));
  }catch(e){}

  try{
    var request=indexedDB.open('PSS_BILLING_DB',1);
    request.onsuccess=function(event){
      var database=event.target.result;
      if(!database.objectStoreNames.contains('bills'))return;
      var transaction=database.transaction('bills','readwrite');
      var store=transaction.objectStore('bills');
      var cursorRequest=store.openCursor();
      var changed=false;
      cursorRequest.onsuccess=function(e){
        var cursor=e.target.result;
        if(!cursor)return;
        var bill=cursor.value;
        var dirty=false;
        var p=String(bill.payment||'CASH').toUpperCase();
        var normalized=p.indexOf('UPI')>=0||p.indexOf('GPAY')>=0?'UPI':p.indexOf('CARD')>=0?'CARD':p.indexOf('CREDIT')>=0?'CREDIT':'CASH';
        if(bill.payment!==normalized){bill.payment=normalized;dirty=true;}
        if(!bill.status){bill.status='SAVED';dirty=true;}
        if(!bill.staffId&&bill.staff){bill.staffId=String(bill.staff).trim().toUpperCase();dirty=true;}
        if(!bill.createdAt){bill.createdAt=(bill.billDate||new Date().toISOString().slice(0,10))+'T00:00:00';dirty=true;}
        if(dirty){cursor.update(bill);changed=true;}
        cursor.continue();
      };
      transaction.oncomplete=function(){
        database.close();
        if(changed&&sessionStorage.getItem('pssV2Migrated')!=='1'){
          sessionStorage.setItem('pssV2Migrated','1');
          location.reload();
        }
      };
    };
  }catch(e){}
})();
