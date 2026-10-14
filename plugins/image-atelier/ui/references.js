'use strict';
var referenceDraft = {vibe:[], precise:[]};
var referenceRender = {vibe:0, precise:0};

async function importLocalImage(file, maxSide) {
  if (!/^image\/(png|jpeg|webp)$/.test(file.type) || file.size > 12000000) throw Error('请选择12MB以内PNG/JPEG/WebP图片');
  var image = new Image(), objectUrl = URL.createObjectURL(file);
  try {
    await new Promise(function(ok, no) { image.onload=ok; image.onerror=function() { no(Error('图片读取失败')); }; image.src=objectUrl; });
    if (!image.width || !image.height || image.width > 8192 || image.height > 8192 || image.width*image.height > 16777216) throw Error('图片尺寸过大');
    var canvas=document.createElement('canvas'), scale=maxSide ? Math.min(1,maxSide/Math.max(image.width,image.height)) : 1;
    canvas.width=Math.round(image.width*scale); canvas.height=Math.round(image.height*scale);
    canvas.getContext('2d').drawImage(image,0,0,canvas.width,canvas.height);
    var encoded=canvas.toDataURL('image/png').split(',')[1];
    if (encoded.length > 16000000) throw Error('转换后的PNG超过素材大小上限');
    var id=newId('input'), count=Math.ceil(encoded.length/24000);
    for (var i=0;i<count;i++) {
      await writeText('nai.asset.'+id+'.'+i,encoded.slice(i*24000,(i+1)*24000));
    }
    await set('nai.asset.'+id,{chunks:count,mime:'image/png',width:canvas.width,height:canvas.height});
    return id;
  } finally { URL.revokeObjectURL(objectUrl); }
}

function fillReferences(defaults) {
  ['vibe','precise'].forEach(function(kind) {
    referenceDraft[kind]=NAI.copy(defaults[kind+'_references'] || []);
    $(kind+'_enabled').checked=defaults[kind+'_enabled'] === true;
    safe(function() { return renderReferences(kind); });
  });
}
function collectReferences(d) {
  ['vibe','precise'].forEach(function(kind) {
    d[kind+'_enabled']=$(kind+'_enabled').checked;
    d[kind+'_references']=NAI.copy(referenceDraft[kind]);
  });
}
async function addReference(kind,id) {
  if (!/^[A-Za-z0-9_-]{8,80}$/.test(id)) throw Error('素材编号无效');
  if (referenceDraft[kind].length>=16) throw Error('最多16张参考图');
  await asset(id);
  referenceDraft[kind].push(kind==='vibe' ? {image_id:id,strength:0.6,information_extracted:$('model').value==='nai-diffusion-4-5-full'?0.7:1}
    : {image_id:id,type:'character&style',strength:1,fidelity:1});
  await renderReferences(kind); say('参考图已添加。尚未启用或编码；底图和默认配置未改变');
}
async function renderReferences(kind) {
  var generation=++referenceRender[kind], root=$(kind+'_list'); root.replaceChildren();
  for (var ref of referenceDraft[kind]) {
    if (generation!==referenceRender[kind]) return;
    (function(r) {
      var card=document.createElement('div'); card.className='card'; root.appendChild(card);
      var id=document.createElement('p'); id.textContent=r.image_id; id.style.overflowWrap='anywhere'; card.appendChild(id);
      var img=document.createElement('img'); img.alt=kind==='vibe'?'Vibe参考图':'精准参考图'; img.style.maxHeight='180px'; img.style.objectFit='contain'; card.appendChild(img);
      asset(r.image_id).then(function(a) { if (generation===referenceRender[kind] && card.isConnected) img.src=a.src; }).catch(function() { img.alt='素材无法读取，请替换'; });
      function field(label,key,min,max) {
        var wrap=document.createElement('label'), input=document.createElement('input');
        wrap.textContent=label; input.type='number'; input.min=min; input.max=max; input.step='0.01'; input.value=r[key];
        input.oninput=function() { r[key]=input.value.trim() ? Number(input.value) : NaN; status.textContent='参数已改变，需重新检查编码状态'; };
        wrap.appendChild(input); card.appendChild(wrap);
      }
      var status=document.createElement('p'); status.className='muted'; status.setAttribute('role','status');
      if (kind==='precise') {
        var label=document.createElement('label'), select=document.createElement('select'); label.textContent='参考类型';
        [['character&style','角色和画风'],['character','只要角色'],['style','只要画风']].forEach(function(pair) {
          var option=document.createElement('option'); option.value=pair[0]; option.textContent=pair[1]; select.appendChild(option);
        }); select.value=r.type; select.onchange=function() { r.type=select.value; }; label.appendChild(select); card.appendChild(label);
      }
      field('参考强度','strength',0,1);
      field(kind==='vibe'?'信息提取量（改变后需重新编码）':'保真度',kind==='vibe'?'information_extracted':'fidelity',kind==='vibe'?0.01:0,1);
      var replacement=document.createElement('label'), file=document.createElement('input'); replacement.textContent='替换此图';
      file.type='file'; file.accept='image/png,image/jpeg,image/webp'; file.onchange=function() { safe(async function() {
        if (!file.files[0]) return; r.image_id=await importLocalImage(file.files[0]); await renderReferences(kind);
      }); }; replacement.appendChild(file); card.appendChild(replacement);
      button(card,'按上方素材编号替换',async function() {
        var replacementId=$(kind+'_asset_id').value.trim();
        if (!/^[A-Za-z0-9_-]{8,80}$/.test(replacementId)) throw Error('请先填写有效素材编号');
        await asset(replacementId); r.image_id=replacementId; await renderReferences(kind);
      });
      button(card,'移除此参考',async function() { referenceDraft[kind]=referenceDraft[kind].filter(function(x) { return x!==r; }); await renderReferences(kind); });
      if (kind==='vibe') {
        var requestIds={};
        function params(preview) {
          var signature=JSON.stringify([r.image_id,$('model').value,r.information_extracted]);
          if (!requestIds[signature]) requestIds[signature]=newId('encode');
          return {image_id:r.image_id,model:$('model').value,information_extracted:r.information_extracted,request_id:requestIds[signature],preview:preview,confirmed:!preview};
        }
        button(card,'检查编码状态 · 免费',async function() {
          var result=await tool('nai_vibe_encode',params(true));
          if (!result.success) throw Error(result.error);
          status.textContent='编码状态：'+result.encoding_status+'；预估新增 '+result.estimated_anlas+' Anlas';
        });
        var encodeButton=button(card,'单独编码 Vibe · 可能扣费',async function() {
          if (working) throw Error('已有任务处理中');
          var snapshot=params(true), preview=await tool('nai_vibe_encode',snapshot);
          if (!preview.success) throw Error(preview.error);
          if (preview.encoding_status==='succeeded') { status.textContent='已编码，相同条件会复用，无需付费'; return; }
          if (preview.existing_request_id) { status.textContent='已有编码请求 '+preview.existing_request_id+'：'+preview.encoding_status+'，请核查平台，不重发'; return; }
          if (!confirm('单独编码这张Vibe参考图，估算2 Anlas。生成另行计费，积分按平台倍率结算。继续？')) return;
          working=true; encodeButton.disabled=true;
          try {
            snapshot.preview=false; snapshot.confirmed=true;
            var result=await tool('nai_vibe_encode',snapshot);
            status.textContent=result.success?'编码已完成，可提交生成':(result.error||result.status);
          } finally { working=false; encodeButton.disabled=false; }
        });
      }
      card.appendChild(status);
    })(ref);
  }
}
function setupReferences() {
  ['vibe','precise'].forEach(function(kind) {
    $(kind+'_add').onclick=function() { safe(function() { return addReference(kind,$(kind+'_asset_id').value.trim()); }); };
    $(kind+'_latest').onclick=function() { safe(async function() {
      var latest=await get('nai.chat.latest',null); if (!latest || !latest.image_id) throw Error('先让机从当前聊天导入图片');
      await addReference(kind,latest.image_id);
    }); };
    $(kind+'_file').onchange=function() { var files=Array.from(this.files); this.value=''; safe(async function() {
      if (referenceDraft[kind].length+files.length>16) throw Error('最多16张参考图');
      for (var file of files) await addReference(kind,await importLocalImage(file));
    }); };
  });
  $('model').onchange=function() { ['vibe','precise'].forEach(function(k) { safe(function() { return renderReferences(k); }); }); };
}
