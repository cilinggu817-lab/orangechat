'use strict';
var NAI = (function() {
  var KEY = 'nai.studio.v2', API = 'https://create.suanbohe.com';
  var DEFAULTS = {
    transport: 'url',
    model: 'nai-diffusion-5-full',
    style: '基础插画',
    negative: '跟随画风',
    characters: [],
    width: 832,
    height: 1216,
    steps: 28,
    scale: 7,
    cfg_rescale: 0.3,
    sampler: 'k_dpmpp_2m',
    noise_schedule: 'karras',
    seed: null,
    mode: 'generate',
    image_id: '',
    strength: 0.7,
    noise: 0,
    i2i_style: '',
    i2i_negative: '',
    vibe_enabled: false,
    vibe_references: [],
    precise_enabled: false,
    precise_references: []
  };
  var PRESETS = {
    styles: {
      '基础插画': { text: 'masterpiece, best quality, detailed illustration', negative: '基础负面' },
      'V5二次元版': { text: 'masterpiece, best quality, 1.4::artist duoqing tie ban shao::, 2.2::artist ruoliang  ::, 1.6::artist colyba::, 0.8::artist dang0 23 ::, 1.3::artist ame jang::, artist poppy yuu, 1.6::artist xiaoye yong dongji guzhang zhong ::, no text,', negative: '基础负面' }
    },
    negatives: {
      '基础负面': { text: 'lowres, bad anatomy, bad hands, text, error, extra digits, fewer digits, worst quality, low quality, watermark, blurry' }
    },
    characters: {}
  };
  var ADV = ['steps', 'scale', 'cfg_rescale', 'sampler', 'noise_schedule', 'seed', 'strength', 'noise'];

  function copy(v) { return JSON.parse(JSON.stringify(v)); }
  function own(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }
  function obj(v) { return v && typeof v === 'object' && !Array.isArray(v); }
  function stop(s) { throw new Error(s); }
  function read(k, f) { var r = dataStore.get(k); return r ? JSON.parse(r) : copy(f); }
  function storeText(k, text) {
    if (dataStore.set(k, text) === false || dataStore.get(k) !== text) stop('本地数据保存失败，请检查存储空间');
  }
  function put(k, v) { dataStore.set(k, JSON.stringify(v)); }
  function putJournal(k, v, allowLegacy) {
    if (typeof dataStore.setDurable !== 'function' && allowLegacy) {
      // Preserve the old host's basic generation path; references still require durable journals.
      var text = JSON.stringify(v); dataStore.set(k, text);
      if (dataStore.get(k) !== text) stop('任务记录保存失败，未自动重发请求');
      return;
    }
    if (typeof dataStore.setDurable !== 'function' || dataStore.setDurable(k, JSON.stringify(v)) !== true) stop('任务记录未能写入磁盘，请更新宿主或检查空间；未自动重发请求');
  }
  function join(a) { return a.filter(function(x) { return typeof x === 'string' && x.trim(); }).map(function(x) { return x.replace(/^[ ,]+|[ ,]+$/g, ''); }).join(', '); }

  function initial() { return { version: 2, revision: 0, defaults: copy(DEFAULTS), presets: copy(PRESETS) }; }
  function state() {
    var s = read(KEY, initial());
    if (s.version !== 2 || !obj(s.defaults) || !obj(s.presets)) stop('工作台配置损坏，请先备份');
    Object.keys(DEFAULTS).forEach(function(k) { if (!own(s.defaults, k)) s.defaults[k] = copy(DEFAULTS[k]); });
    return s;
  }

  function num(v, k, a, b, i) { if (typeof v !== 'number' || !isFinite(v) || v < a || v > b || (i && Math.floor(v) !== v)) stop(k + '数值不合法'); }
  function preset(map, n) { if (!own(map, n) || !obj(map[n]) || typeof map[n].text !== 'string') stop('预设不存在：' + n); return map[n]; }

  function validate(d, p) {
    if (!obj(p) || !obj(p.styles) || !obj(p.negatives) || !obj(p.characters)) stop('预设格式错误');
    ['styles', 'negatives', 'characters'].forEach(function(kind) {
      Object.keys(p[kind]).forEach(function(name) {
        if (!name.trim() || ['__proto__','constructor','prototype'].indexOf(name) >= 0) stop('预设名称无效');
        var item = preset(p[kind], name);
        if (kind === 'styles' && own(item, 'negative')) {
          if (typeof item.negative !== 'string') stop('画风配套负面格式错误');
          if (item.negative) preset(p.negatives, item.negative);
        }
      });
    });
    if (['url', 'native'].indexOf(d.transport) < 0) stop('通道无效');
    if (!/^nai-diffusion-(4-5|5)-(full|curated)$/.test(d.model)) stop('模型无效');
    if (['generate', 'img2img'].indexOf(d.mode) < 0) stop('生成模式无效');
    if (d.style) preset(p.styles, d.style);
    if (d.negative !== '跟随画风' && d.negative) preset(p.negatives, d.negative);
    if (d.i2i_style) preset(p.styles, d.i2i_style);
    if (d.i2i_negative) preset(p.negatives, d.i2i_negative);
    num(d.width, '宽度', 64, 2048, true); num(d.height, '高度', 64, 2048, true);
    if (d.width % 64 || d.height % 64 || d.width * d.height > 2097152) stop('尺寸需为64倍数且总像素不超过2097152');
    num(d.steps, '步数', 1, 50, true); num(d.scale, '引导', 0, 20); num(d.cfg_rescale, '重缩放', 0, 1); num(d.strength, '重绘', 0, 1); num(d.noise, '噪声', 0, 1);
    if (d.seed !== null) num(d.seed, '种子', 0, 4294967295, true);
    if (['k_dpmpp_2m', 'k_dpmpp_sde', 'k_euler', 'k_euler_ancestral', 'k_dpmpp_2s_ancestral', 'ddim_v3'].indexOf(d.sampler) < 0) stop('采样器无效');
    if (['karras', 'native', 'exponential', 'polyexponential'].indexOf(d.noise_schedule) < 0) stop('噪声调度无效');
    if (!Array.isArray(d.characters) || d.characters.length > 6) stop('人物卡最多选择6个');
    d.characters.forEach(function(n) { preset(p.characters, n); });
    ['vibe', 'precise'].forEach(function(kind) {
      if (typeof d[kind + '_enabled'] !== 'boolean') stop(kind + '启用状态必须为布尔值');
      var refs = d[kind + '_references'];
      if (!Array.isArray(refs) || refs.length > 16) stop(kind + '参考最多16张');
      refs.forEach(function(r) {
        if (!obj(r) || !/^[A-Za-z0-9_-]{8,80}$/.test(r.image_id || '')) stop(kind + '参考素材编号无效');
        var allowed = kind === 'vibe' ? ['image_id','strength','information_extracted'] : ['image_id','strength','fidelity','type'];
        Object.keys(r).forEach(function(k) { if (allowed.indexOf(k) < 0) stop('未知参考参数：' + k); });
        num(r.strength, '参考强度', 0, 1);
        if (kind === 'vibe') num(r.information_extracted, '信息提取', 0.01, 1);
        else {
          num(r.fidelity, '保真度', 0, 1);
          if (['character&style','character','style'].indexOf(r.type) < 0) stop('精准参考类型无效');
        }
      });
    });
  }

  // Preset management is an explicit save; keep all persisted references consistent.
  function editPreset(s, kind, oldName, name, item) {
    if (['styles','negatives','characters'].indexOf(kind) < 0) stop('预设类型无效');
    var n = copy(s), map = n.presets[kind], removing = item === null;
    if (oldName && !own(map, oldName)) stop('原预设不存在');
    if (!removing) {
      if (typeof name !== 'string' || !name.trim() || ['__proto__','constructor','prototype'].indexOf(name) >= 0 || (kind === 'negatives' && name === '跟随画风')) stop('预设名称无效');
      if (name !== oldName && own(map, name)) stop('同名预设已存在，请选择该预设后编辑');
      map[name] = copy(item);
    }
    if (oldName && (removing || name !== oldName)) {
      delete map[oldName];
      var replacement = removing ? '' : name;
      var keys = kind === 'styles' ? ['style','i2i_style'] : kind === 'negatives' ? ['negative','i2i_negative'] : [];
      keys.forEach(function(k) { if (n.defaults[k] === oldName) n.defaults[k] = replacement; });
      if (kind === 'characters') n.defaults.characters = n.defaults.characters.map(function(v) { return v === oldName ? replacement : v; }).filter(Boolean);
      if (kind === 'negatives') Object.keys(n.presets.styles).forEach(function(k) {
        if (n.presets.styles[k].negative === oldName) n.presets.styles[k].negative = replacement;
      });
    }
    validate(n.defaults, n.presets);
    return n;
  }

  function activeReferences(d) {
    var vibes = d.vibe_enabled ? d.vibe_references : [], precise = d.precise_enabled ? d.precise_references : [];
    if (d.vibe_enabled && !vibes.length) stop('Vibe已启用，请选择参考图或关闭');
    if (d.precise_enabled && !precise.length) stop('精准参考已启用，请选择参考图或关闭');
    if (vibes.length || precise.length) {
      if (d.transport !== 'native') stop('参考功能需要原生通道；URL通道不支持，请切换');
      if (!/^nai-diffusion-4-5-(full|curated)$/.test(d.model)) stop('平台当前仅V4.5支持Vibe和精准参考；请明确选择V4.5或关闭参考');
      if (vibes.length && precise.length) stop('Vibe与精准参考不能同时使用；请关闭其中一种');
    }
    return { vibe: copy(vibes), precise: copy(precise) };
  }

  function plan(s, prompt, over) {
    if (typeof prompt !== 'string' || !prompt.trim()) stop('请填写画面描述');
    if (!obj(over)) stop('参数必须是对象');
    var d = copy(s.defaults);
    Object.keys(over).forEach(function(k) { if (!own(DEFAULTS, k)) stop('未知参数：'+ k); d[k] = copy(over[k]); });
    if (d.mode === 'img2img' && !own(over, 'seed')) d.seed = null;
    validate(d, s.presets);
    var references = activeReferences(d);
    if (d.transport === 'url') {
      if (d.mode !== 'generate') stop('图生图需要原生通道，URL通道不支持底图重绘');
      ADV.forEach(function(k) { if (own(over, k)) stop('URL通道不支持自定义高级参数 ' + k); });
    }
    var styleName = d.mode === 'img2img' ? d.i2i_style : d.style;
    var negChoice = d.mode === 'img2img' ? d.i2i_negative : d.negative;
    var fallback = own(s.presets.negatives, '基础负面') ? '基础负面' : (Object.keys(s.presets.negatives)[0] || '');
    var st = styleName ? preset(s.presets.styles, styleName) : { text: '', negative: fallback };
    var nn = negChoice === '跟随画风' ? (own(st, 'negative') ? st.negative : fallback) : negChoice;
    var neg = nn ? preset(s.presets.negatives, nn).text : '';
    var base = join([st.text, prompt]);
    var chars = d.characters.map(function(n) { return preset(s.presets.characters, n).text; });
    var body, path;
    if (d.transport === 'url') {
      path = '/api/openai/v1/images/generations';
      body = { model: d.model, prompt: join([base].concat(chars)), size: d.width + 'x' + d.height, negative_prompt: neg, response_format: 'url' };
    } else {
      path = '/api/ai/generate-image';
      var params = {
        width: d.width, height: d.height, steps: d.steps, scale: d.scale, cfg_rescale: d.cfg_rescale,
        sampler: d.sampler, noise_schedule: d.noise_schedule, n_samples: 1, seed: d.seed, uc: neg,
        v4_prompt: { caption: { base_caption: base, char_captions: chars.map(function(t) { return { char_caption: t, centers: [{ x: 0.5, y: 0.5 }] }; }) }, use_coords: false, use_order: true },
        v4_negative_prompt: { caption: { base_caption: neg, char_captions: [] } }
      };
      if (d.mode === 'img2img') {
        if (!/^[a-zA-Z0-9_-]{8,80}$/.test(d.image_id)) stop('请先选择底图');
        params.strength = d.strength; params.noise = d.noise;
      }
      if (references.vibe.length) {
        params.params_version = 3;
        var total = references.vibe.reduce(function(n, r) { return n + r.strength; }, 0);
        params.reference_image_multiple = references.vibe.map(function(r) { return '[encoded local asset: ' + r.image_id + ']'; });
        params.reference_strength_multiple = references.vibe.map(function(r) { return references.vibe.length > 1 && total > 1 ? r.strength / total : r.strength; });
        params.normalize_reference_strength_multiple = true;
      }
      if (references.precise.length) {
        params.params_version = 3;
        params.director_reference_images = references.precise.map(function(r) { return '[prepared local asset: ' + r.image_id + ']'; });
        params.director_reference_descriptions = references.precise.map(function(r) { return {caption:{base_caption:r.type,char_captions:[]},legacy_uc:false}; });
        params.director_reference_information_extracted = references.precise.map(function() { return 1; });
        params.director_reference_strength_values = references.precise.map(function(r) { return r.strength; });
        params.director_reference_secondary_strength_values = references.precise.map(function(r) { return Math.round((1-r.fidelity)*100)/100; });
      }
      body = { input: base, model: d.model, action: d.mode, parameters: params };
    }
    return { effective: d, references: references, negative_name: nn, path: path, body: body,
      fees: { vibe_encoding_anlas_each: 2, reference_generation_anlas_estimate: 2 * Math.max(0, references.vibe.length - 4) + 5 * references.precise.length,
        note: '参考附加费用估算，不含基础生成；实际积分由平台Anlas消耗、倍率及套餐决定。导入/预览免费；生成不会自动编码。' } };
  }

  function url(v) { return typeof v === 'string' && /^https:\/\/[^\s<>"()]+$/i.test(v); }
  function publicJob(j) {
    var o = { success: j.status === 'succeeded', id: j.id, status: j.status, created: j.created, effective: j.effective, error: j.error || '', has_local_image: !!j.asset };
    if (j.url) { o.url = j.url; o.markdown = '![绘图结果](' + j.url + ')'; }
    if (j.asset && j.status === 'succeeded') o._orange_image = { version: 1, asset_key: 'nai.asset.' + j.asset };
    return o;
  }

  function result(id) {
    if (id === 'demo') return { success: false, error: '公共版不携带私人测试图片。请从 nai_status 选择已有成功任务，用 nai_result 免费读取。' };
    if (!/^[a-zA-Z0-9_-]{8,80}$/.test(id)) stop('任务编号不正确');
    var j = read('nai.job.' + id, null);
    if (!j) stop('任务不存在');
    return publicJob(j);
  }

  function assetPut(id, b64, mime) {
    if (b64.length > 16000000) stop('图片过大');
    var n = Math.ceil(b64.length / 24000);
    for (var i = 0; i < n; i++) dataStore.set('nai.asset.' + id + '.' + i, b64.slice(i * 24000, (i + 1) * 24000));
    put('nai.asset.' + id, { chunks: n, mime: mime });
    return id;
  }
  // Generation-only write checks; leave the established upscale storage path intact.
  function resultAssetPut(id, b64, mime) {
    if (typeof b64 !== 'string' || !b64.length || b64.length > 16000000 || b64.length % 4 || !/^[A-Za-z0-9+/]+={0,2}$/.test(b64)) stop('图片编码无效或过大');
    var n = Math.ceil(b64.length / 24000);
    for (var i = 0; i < n; i++) storeText('nai.asset.' + id + '.' + i, b64.slice(i * 24000, (i + 1) * 24000));
    storeText('nai.asset.' + id, JSON.stringify({ chunks: n, mime: mime }));
    return id;
  }
  function assetGet(id) {
    var a = read('nai.asset.' + id, null);
    if (!a || !Number.isInteger(a.chunks) || a.chunks < 1 || a.chunks > 700) stop('底图不存在或损坏');
    var s = '';
    for (var i = 0; i < a.chunks; i++) {
      var v = dataStore.get('nai.asset.' + id + '.' + i);
      if (!v) stop('底图分片丢失');
      s += v;
    }
    return s;
  }

  function status() {
    var s = state(); validate(s.defaults, s.presets);
    var ids = read('nai.history', []);
    return {
      success: true,
      version: '0.3.9',
      defaults: s.defaults,
      styles: Object.keys(s.presets.styles),
      negatives: Object.keys(s.presets.negatives),
      characters: Object.keys(s.presets.characters),
      references: { models: ['nai-diffusion-4-5-full','nai-diffusion-4-5-curated'], max_images: 16,
        vibe: 'vibe_enabled + vibe_references:[{image_id,strength:0.6,information_extracted:0.7}]；先nai_vibe_encode单独编码',
        precise: 'precise_enabled + precise_references:[{image_id,type:"character&style",strength:1,fidelity:1}]；type也可character或style；不绑定人物卡',
        disable: '单次overrides传vibe_enabled:false、precise_enabled:false；不修改默认',
        incompatible: 'Vibe和精准参考互斥；均不支持URL或V5；均独立于图生图底图' },
      encodings: read('nai.encode.history', []).slice(0, 10).map(function(id) { return publicEncoding(read('nai.encode.job.' + id, {})); }),
      recent: ids.slice(0, 10).map(function(id) {
        var j = read('nai.job.' + id, null);
        return j ? { id: id, status: j.status } : null;
      }).filter(Boolean)
    };
  }

  async function draw(p) {
    var id = p.request_id;
    if (!/^[a-zA-Z0-9_-]{8,80}$/.test(id || '')) stop('request_id 无效');
    if (!p.preview) {
      var existing = read('nai.job.' + id, null);
      if (existing) return publicJob(existing);
    }
    var over = p.overrides ? JSON.parse(p.overrides) : {};
    var q = plan(state(), p.prompt, over);
    if (p.preview) return { success: true, preview: true, effective: q.effective, request: q.body, references: referencePreview(q), fees: q.fees };

    var key = typeof config === 'object' ? String(config.api_key || '').trim() : '';
    if (!key) stop('请先在插件设置中填写酸薄荷 API Key');

    if (q.effective.transport === 'native') {
      if (q.effective.mode === 'generate') {
        if (q.body.parameters.seed === null) q.body.parameters.seed = Math.floor(Math.random() * 4294967296);
      } else {
        if (q.body.parameters.seed === null) delete q.body.parameters.seed;
      }
      q.effective.seed = (q.body.parameters.seed === undefined || q.body.parameters.seed === null) ? null : q.body.parameters.seed;
      if (q.effective.mode === 'img2img') q.body.parameters.image = assetGet(q.effective.image_id);
    }

    var referencePayload = prepareReferences(q);
    var j = { id: id, status: 'sending', created: new Date().toISOString(), effective: q.effective, prompt: p.prompt, request: copy(q.body) };
    if (j.request.parameters) delete j.request.parameters.image;
    // Encoded reference bytes are only attached to the outbound request after the safe task snapshot.
    if (q.body.parameters) Object.keys(referencePayload).forEach(function(k) { q.body.parameters[k] = referencePayload[k]; });

    // 记录历史（最多保留15条）
    putJournal('nai.job.' + id, j, !q.references.vibe.length && !q.references.precise.length);
    var hist = [id].concat(read('nai.history', []).filter(function(x) { return x !== id; })).slice(0, 15);
    storeText('nai.history', JSON.stringify(hist));

    try {
      var res = await fetch(API + q.path, {
        method: 'POST',
        retry: false,
        headers: { 'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json', 'Accept': 'application/json', 'Idempotency-Key': id },
        body: JSON.stringify(q.body)
      });
      if (!res.ok) {
        j.status = 'failed';
        j.error = '平台返回 HTTP ' + res.status;
      } else {
        j.status = 'response_unreadable';
        var data = await res.json(), item = (data.data && data.data[0]) || (data.images && data.images[0]);
        if (!item) stop('返回内容里没有图片');
        if (item.url && url(item.url)) {
          j.url = item.url;
          j.status = 'succeeded';
        } else {
          var b = typeof item === 'string' ? item : (item.b64_json || item.image || item.base64);
          if (typeof b !== 'string') stop('未识别到图片格式');
          b = b.replace(/^data:image\/[a-zA-Z0-9.+-]+;base64,/, '').replace(/\s/g, '');
          // Some JSON adapters omit optional Base64 padding; preserve the decoded bytes.
          if (b.indexOf('=') < 0 && b.length % 4 > 1) b += b.length % 4 === 2 ? '==' : '=';
          var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/', header = [], bits = 0, acc = 0;
          for (var h = 0; h < Math.min(b.length, 24); h++) {
            var value = chars.indexOf(b[h]); if (value < 0) break;
            acc = (acc << 6) | value; bits += 6;
            if (bits >= 8) { bits -= 8; header.push((acc >>> bits) & 255); }
          }
          var mime = header.slice(0,8).join(',') === '137,80,78,71,13,10,26,10' ? 'image/png'
            : header.slice(0,3).join(',') === '255,216,255' ? 'image/jpeg'
            : header.slice(0,4).join(',') === '82,73,70,70' && header.slice(8,12).join(',') === '87,69,66,80' ? 'image/webp' : '';
          if (!mime || b.length < 32) stop('返回内容不是支持的图片');
          j.asset = resultAssetPut(id, b, mime);
          j.status = 'succeeded';
        }
      }
    } catch (e) {
      // 网络/超时异常，标记状态并温和提示
      j.status = j.status === 'response_unreadable' ? 'response_unreadable' : 'timeout_or_network';
      j.error = '请求或结果未确认，可能已扣费；请核查平台作品库，不要换编号重发。';
    }

    putJournal('nai.job.' + id, j, !q.references.vibe.length && !q.references.precise.length);
    return publicJob(j);
  }


  function pngSize(b) {
    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/', bytes = [], bits = 0, acc = 0;
    for (var i = 0; i < Math.min(b.length, 48); i++) {
      var v = chars.indexOf(b[i]); if (v < 0) break;
      acc = (acc << 6) | v; bits += 6;
      if (bits >= 8) { bits -= 8; bytes.push((acc >>> bits) & 255); }
    }
    if (bytes.slice(0,8).join(',') !== '137,80,78,71,13,10,26,10' || bytes.slice(12,16).join(',') !== '73,72,68,82') stop('放大底图须为PNG；请在页面重新选择图片以转换格式');
    function u32(k) { return bytes[k]*16777216 + bytes[k+1]*65536 + bytes[k+2]*256 + bytes[k+3]; }
    var w=u32(16), h=u32(20); num(w,'原图宽',1,4096,true); num(h,'原图高',1,4096,true);
    return {width:w,height:h};
  }
  async function upscale(p) {
    var id=p.request_id;
    if (!/^[a-zA-Z0-9_-]{8,80}$/.test(id || '')) stop('request_id 无效');
    var existing=read('nai.job.'+id,null);
    if (existing) return publicJob(existing);
    if (!/^[a-zA-Z0-9_-]{8,80}$/.test(p.image_id || '')) stop('底图编号无效');
    if (p.scale !== undefined && p.scale !== 2) stop('本版只支持宽高各2倍');
    var b=assetGet(p.image_id), size=pngSize(b);
    if(size.width*size.height>1048576) stop('本版放大输入上限为1048576像素，请先缩小底图');
    var effective={mode:'upscale',transport:'native',image_id:p.image_id,scale:2,width:size.width*2,height:size.height*2};
    if(p.preview===true) return {success:true,preview:true,effective:effective,input:size,path:'/api/ai/upscale'};
    if(p.confirmed!==true) stop('放大可能扣费，需明确确认后传 confirmed=true');
    var key=typeof config==='object'?String(config.api_key||'').trim():'';
    if(!key) stop('请先在插件设置中填写酸薄荷 API Key');
    var j={id:id,status:'sending',created:new Date().toISOString(),effective:effective,kind:'upscale',source_asset:p.image_id};
    put('nai.job.'+id,j); put('nai.history',[id].concat(read('nai.history',[]).filter(function(x){return x!==id;})).slice(0,15));
    var res;
    try {
      res=await fetch(API+'/api/ai/upscale',{method:'POST',headers:{'Authorization':'Bearer '+key,'Content-Type':'application/json','Accept':'application/json','Idempotency-Key':id},body:JSON.stringify({image:b,width:size.width,height:size.height,scale:2})});
    } catch(e) {
      j.status='timeout_or_network';j.error='请求中断，扣费与结果未确认。请核查平台记录，不要用新编号重发。';
      put('nai.job.'+id,j);return publicJob(j);
    }
    if(!res.ok){j.status='failed';j.error='平台返回 HTTP '+res.status+'；请核查平台记录。';}
    else {
      try {
        var data=await res.json();
        var item=(data&&Array.isArray(data.data)&&data.data[0])||(data&&Array.isArray(data.images)&&data.images[0])||(data&&data.data&&typeof data.data==='object'&&data.data)||data;
        if(item&&url(item.url)){j.url=item.url;j.status='succeeded';}
        else {
          var out=typeof item==='string'?item:item&&(item.b64_json||item.image||item.base64);
          if(typeof out!=='string') stop('结果缺少图片');
          out=out.replace(/^data:image\/[a-zA-Z0-9.+-]+;base64,/,'').replace(/\s/g,'');
          if(!/^[A-Za-z0-9+/]+={0,2}$/.test(out)||out.length%4===1) stop('图片编码无效');
          var actual=pngSize(out);
          if(actual.width!==effective.width||actual.height!==effective.height) stop('返回图尺寸与2倍放大不一致');
          j.asset=assetPut(id,out,'image/png');j.status='succeeded';
        }
      } catch(e) {j.status='response_unreadable';j.error='平台已响应，但结果格式或本地保存未完成；可能已扣费。请核查平台作品库，不要重发。';}
    }
    put('nai.job.'+id,j);return publicJob(j);
  }
  // Vibe encoding has its own journal and opaque asset store, separate from generation jobs.
  function prepared(id, purpose) {
    if (typeof imageAssets !== 'object' || typeof imageAssets.prepare !== 'function') stop('参考功能需要0.3.7配套宿主，请先更新橘瓣');
    return imageAssets.prepare(id, purpose);
  }
  function encodingIdentity(ref, model) {
    var a = prepared(ref.image_id, 'vibe');
    return { image: a, key: 'nai.vibe.cache.' + a.source_sha256 + '.' + model + '.' + ref.information_extracted + '.' + a.preparation };
  }
  function publicEncoding(j) {
    return { success: j.status === 'succeeded', id: j.id, status: j.status, image_id: j.image_id, model: j.model,
      information_extracted: j.information_extracted, error: j.error || '', reused: !!j.reused };
  }
  function referencePreview(q) {
    return { vibe: q.references.vibe.map(function(r) {
      var identity = encodingIdentity(r, q.effective.model), record = read(identity.key, null);
      return { image_id: r.image_id, strength: r.strength, information_extracted: r.information_extracted,
        encoding_status: record ? record.status : 'not_encoded', encoding_request_id: record ? record.id : null };
    }), precise: q.references.precise.map(function(r) {
      // Read/prepare locally so a missing asset fails before a paid request.
      prepared(r.image_id, 'precise'); return copy(r);
    }) };
  }
  function prepareReferences(q) {
    var out = {}, vibes = q.references.vibe, precise = q.references.precise;
    if (vibes.length) {
      out.reference_image_multiple = vibes.map(function(r) {
        var identity = encodingIdentity(r, q.effective.model), record = read(identity.key, null);
        if (!record || record.status !== 'succeeded') stop('Vibe素材' + r.image_id + '在当前模型/信息提取量下尚未编码成功。先调用nai_vibe_encode；生成不会自动付费编码');
        var encoded = dataStore.get('nai.vibe.encoding.' + record.id);
        if (!encoded) stop('Vibe编码本地读取失败；请核查既有任务，不要重发');
        return encoded;
      });
      var sum = vibes.reduce(function(n, r) { return n + r.strength; }, 0);
      out.reference_strength_multiple = vibes.map(function(r) { return vibes.length > 1 && sum > 1 ? r.strength / sum : r.strength; });
      out.normalize_reference_strength_multiple = true;
    }
    if (precise.length) {
      out.director_reference_images = precise.map(function(r) { return assetGet(prepared(r.image_id, 'precise').image_id); });
      out.director_reference_descriptions = precise.map(function(r) { return { caption: {base_caption:r.type, char_captions:[]}, legacy_uc:false }; });
      out.director_reference_information_extracted = precise.map(function() { return 1; });
      out.director_reference_strength_values = precise.map(function(r) { return r.strength; });
      out.director_reference_secondary_strength_values = precise.map(function(r) { return Math.round((1-r.fidelity)*100)/100; });
    }
    return out;
  }
  async function encodeVibe(p) {
    var id = p.request_id;
    if (!/^[a-zA-Z0-9_-]{8,80}$/.test(id || '')) stop('request_id无效');
    var prior = read('nai.encode.job.' + id, null);
    if (prior && !p.preview) {
      if (prior.image_id !== p.image_id || prior.model !== p.model || prior.information_extracted !== p.information_extracted) stop('request_id已用于另一组编码参数；已有任务未重发，请用预览核对本次设置');
      return publicEncoding(prior);
    }
    if (!/^nai-diffusion-4-5-(full|curated)$/.test(p.model || '')) stop('Vibe编码仅支持已核实的V4.5模型');
    num(p.information_extracted, '信息提取', 0.01, 1);
    var identity = encodingIdentity(p, p.model), cached = read(identity.key, null);
    if (p.preview) return { success:true, preview:true, image_id:p.image_id, model:p.model, information_extracted:p.information_extracted,
      encoding_status:cached ? cached.status : 'not_encoded', existing_request_id:cached ? cached.id : null,
      estimated_anlas:cached && cached.status === 'succeeded' ? 0 : 2, fee_note:'独立编码，生成另行计费；积分以平台实际倍率结算' };
    if (cached) {
      var reused = copy(cached); reused.reused = cached.status === 'succeeded';
      if (!reused.reused) reused.error = '同源图/模型/提取量已有编码请求，状态未成功；请核查平台，不自动重发。' + (cached.error || '');
      return publicEncoding(reused);
    }
    if (p.confirmed !== true) stop('Vibe编码单独计费（估算2 Anlas），确认后传confirmed=true');
    var key = typeof config === 'object' ? String(config.api_key || '').trim() : '';
    if (!key) stop('请先填写酸薄荷API Key');
    var j = { id:id, status:'sending', image_id:p.image_id, model:p.model, information_extracted:p.information_extracted, created:new Date().toISOString() };
    putJournal(identity.key, j); putJournal('nai.encode.job.'+id, j);
    put('nai.encode.history', [id].concat(read('nai.encode.history', []).filter(function(x) { return x !== id; })).slice(0, 30));
    try {
      var response = await fetch(API + '/api/ai/encode-vibe', {method:'POST', responseEncoding:'base64', retry:false,
        headers:{Authorization:'Bearer '+key, 'Content-Type':'application/json', Accept:'application/octet-stream', 'Idempotency-Key':id},
        body:JSON.stringify({image:assetGet(identity.image.image_id), model:p.model, information_extracted:p.information_extracted})});
      if (!response.ok) { j.status='failed'; j.error='平台返回HTTP '+response.status+'；请核查API Key的Anlas权限与平台记录'; }
      else {
        j.status='response_unreadable';
        var type = response.headers && (response.headers['content-type'] || response.headers['Content-Type']) || '';
        if (!/application\/(octet-stream|x-binary)/i.test(type)) stop('编码响应不是已核实的二进制格式');
        if (typeof response.base64 !== 'function') stop('宿主不支持无损二进制响应');
        var encoded = response.base64();
        if (typeof encoded !== 'string' || !/^[A-Za-z0-9+/]+={0,2}$/.test(encoded) || encoded.length > 16000000) stop('编码结果无效');
        if (dataStore.setDurable('nai.vibe.encoding.'+id, encoded) !== true) stop('编码存储失败');
        j.status='succeeded';
      }
    } catch(e) {
      j.status = j.status === 'response_unreadable' ? 'response_unreadable' : 'timeout_or_network';
      j.error = '编码结果未确认，可能已扣费；请核查平台，不换编号或自动重发。';
    }
    putJournal(identity.key,j); putJournal('nai.encode.job.'+id,j);
    return publicEncoding(j);
  }
  function encodingResult(id) {
    if (!/^[a-zA-Z0-9_-]{8,80}$/.test(id || '')) stop('编码任务编号无效');
    var j = read('nai.encode.job.'+id, null); if (!j) stop('编码任务不存在'); return publicEncoding(j);
  }
  return { KEY: KEY, initial: initial, validate: validate, editPreset: editPreset, plan: plan, status: status, result: result, draw: draw, upscale: upscale, copy: copy,
    encodeVibe:encodeVibe, encodingResult:encodingResult };
})();

function guarded(fn) {
  return async function(p) {
    try { return await fn(p || {}); }
    catch (e) { return { success: false, error: e && e.message ? e.message : '执行失败' }; }
  };
}

exports.nai_status = guarded(function() { return NAI.status(); });
exports.nai_result = guarded(function(p) { return NAI.result(p.id); });
exports.nai_draw = guarded(function(p) { return NAI.draw(p); });

exports.nai_upscale = guarded(function(p) { return NAI.upscale(p); });
exports.nai_chat_image = guarded(function() { return {success:false,error:'聊天附件导入需要配套橘瓣宿主补丁；当前宿主未提供此能力。'}; });
exports.nai_vibe_encode = guarded(function(p) { return NAI.encodeVibe(p); });
exports.nai_vibe_result = guarded(function(p) { return NAI.encodingResult(p.id); });
