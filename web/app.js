(function () {
  'use strict';

  var bridge = window.MedApp || null;
  var state = {
    tab: 'today',
    view: 'main',
    subTab: 'meds',
    today: null,
    plan: null,
    history: null,
    status: null,
    fontScale: 1,
    sheetOpen: false,
    editingId: '',
    editingOrigin: '',
    photoCache: {},
    error: ''
  };

  var main = document.getElementById('main');
  var sheet = document.getElementById('sheet');
  var toastEl = document.getElementById('toast');
  var toastTimer = null;

  function api(name) {
    if (!bridge || typeof bridge[name] !== 'function') {
      throw new Error('当前不在 App 内，无法保存');
    }
    var args = Array.prototype.slice.call(arguments, 1);
    var raw = bridge[name].apply(bridge, args);
    if (!raw) return {};
    return JSON.parse(raw);
  }

  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (ch) {
      return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch];
    });
  }

  function showToast(text) {
    toastEl.textContent = text;
    toastEl.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      toastEl.classList.remove('show');
    }, 2600);
  }

  var confirmDialogEl = null;
  var activeDialogCancel = null;

  function buildConfirmDialog() {
    var d = document.createElement('dialog');
    d.className = 'confirm-dialog';
    d.setAttribute('aria-modal', 'true');
    d.innerHTML =
      '<h2 class="dialog-title"></h2>' +
      '<p class="dialog-text"></p>' +
      '<div class="dialog-actions">' +
      '<button type="button" class="btn btn-lg btn-block dialog-confirm"></button>' +
      '<button type="button" class="btn btn-secondary btn-lg btn-block dialog-cancel">取消</button>' +
      '</div>';
    document.body.appendChild(d);
    return d;
  }

  function confirmDialog(options) {
    var opts = options || {};
    var d = confirmDialogEl || (confirmDialogEl = buildConfirmDialog());
    if (typeof d.showModal !== 'function') {
      return Promise.resolve(window.confirm(
        (opts.title || '确认') + '\n' + (opts.message || '')));
    }

    d.querySelector('.dialog-title').textContent = opts.title || '确认';
    var textEl = d.querySelector('.dialog-text');
    textEl.textContent = opts.message || '';
    textEl.style.display = opts.message ? '' : 'none';
    var confirmBtn = d.querySelector('.dialog-confirm');
    var cancelBtn = d.querySelector('.dialog-cancel');
    confirmBtn.textContent = opts.confirmLabel || '确认';
    confirmBtn.classList.toggle('btn-danger', !!opts.danger);
    confirmBtn.classList.toggle('btn-primary', !opts.danger);
    cancelBtn.textContent = opts.cancelLabel || '取消';

    return new Promise(function (resolve) {
      var settled = false;
      function finish(value) {
        if (settled) return;
        settled = true;
        activeDialogCancel = null;
        confirmBtn.onclick = null;
        cancelBtn.onclick = null;
        d.oncancel = null;
        if (d.open) {
          try { d.close(); } catch (ignored) {}
        }
        resolve(value);
      }
      confirmBtn.onclick = function () { finish(true); };
      cancelBtn.onclick = function () { finish(false); };
      d.oncancel = function (event) {
        event.preventDefault();
        finish(false);
      };
      activeDialogCancel = function () { finish(false); };
      d.showModal();
      try { cancelBtn.focus(); } catch (ignored) {}
    });
  }

  function applyFont(scale) {
    var value = Number(scale) || 1;
    state.fontScale = value;
    document.documentElement.style.fontSize = (20 * value).toFixed(1) + 'px';
  }

  function uiMode() {
    if (state.plan && state.plan.uiMode) return state.plan.uiMode;
    if (state.today && state.today.uiMode) return state.today.uiMode;
    return 'normal';
  }

  function applyMode() {
    try {
      document.documentElement.setAttribute('data-mode', uiMode());
    } catch (ignored) {}
  }

  function formatTime(iso) {
    if (!iso || iso.length < 16) return '';
    return iso.substring(11, 16);
  }

  function getPhotoData(medId) {
    if (!medId) return '';
    if (!state.photoCache) state.photoCache = {};
    if (Object.prototype.hasOwnProperty.call(state.photoCache, medId)) {
      return state.photoCache[medId];
    }
    var data = '';
    try {
      if (bridge && typeof bridge.getPhoto === 'function') {
        data = bridge.getPhoto(medId) || '';
      }
    } catch (ignored) {}
    state.photoCache[medId] = data;
    return data;
  }

  function clearPhotoCache(medId) {
    if (!state.photoCache) state.photoCache = {};
    if (medId) delete state.photoCache[medId];
    else state.photoCache = {};
  }

  function renderPhotoBox() {
    var box = document.getElementById('photoBox');
    if (!box) return;
    var medId = state.editingId;
    if (!medId) {
      box.innerHTML = '<p class="small muted">先保存这条药，再回来添加照片。</p>';
      return;
    }
    var data = getPhotoData(medId);
    if (data) {
      box.innerHTML = '<img class="photo-preview" src="' + data + '" alt="药品照片">' +
        '<div class="photo-actions">' +
        '<button type="button" class="btn btn-secondary" data-action="pick-photo">更换</button>' +
        '<button type="button" class="btn btn-secondary" data-action="remove-photo">删除</button>' +
        '</div>';
    } else {
      box.innerHTML = '<p class="small muted">还没有照片。照片只做辅助识别，不会替代药名文字。</p>' +
        '<button type="button" class="btn btn-secondary btn-block" data-action="pick-photo">选择照片</button>';
    }
  }

  function loadView() {
    state.error = '';
    state.today = api('getToday');
    state.status = api('getStatus');
    state.plan = api('getPlan');
    if (state.view === 'main' && state.tab === 'history') {
      state.history = api('getHistory', 14);
    }
    if (state.view === 'sub' && state.subTab === 'history') {
      state.history = api('getHistory', 14);
    }
    if (state.today && state.today.fontScale) {
      applyFont(state.today.fontScale);
    }
    applyMode();
  }

  function switchTab(tab) {
    if (state.sheetOpen) closeSheet(false);
    state.tab = tab;
    state.view = 'main';
    try {
      loadView();
      render();
      window.scrollTo(0, 0);
    } catch (error) {
      state.error = String(error && error.message ? error.message : error);
      render();
    }
  }

  function openView(view) {
    if (state.sheetOpen) closeSheet(false);
    state.view = view;
    try {
      loadView();
      render();
      window.scrollTo(0, 0);
    } catch (error) {
      state.error = String(error && error.message ? error.message : error);
      render();
    }
  }

  function settingsBack() {
    if (state.sheetOpen) {
      closeSheet(false);
      return;
    }
    if (state.view === 'settings-status'
        || state.view === 'settings-debug'
        || state.view === 'settings-about') {
      openView('settings');
      return;
    }
    if (state.view === 'sub') {
      openView('settings');
      return;
    }
    if (state.view === 'settings') {
      openView('main');
    }
  }

  function openSub(tab) {
    state.subTab = tab;
    openView('sub');
  }

  // 从药品编辑返回：长辈版回设置里的二级页，正常版回药品标签。
  function returnToMeds() {
    if (uiMode() === 'care') {
      state.subTab = 'meds';
      state.view = 'sub';
    } else {
      state.tab = 'meds';
      state.view = 'main';
    }
    loadView();
    render();
  }

  function reload() {
    try {
      loadView();
      render();
    } catch (error) {
      state.error = String(error && error.message ? error.message : error);
      render();
    }
  }

  function viewTitle() {
    if (state.view === 'settings') return '设置';
    if (state.view === 'settings-status') return '提醒状态';
    if (state.view === 'settings-debug') return '调试';
    if (state.view === 'settings-about') return '关于';
    if (state.view === 'sub') return state.subTab === 'history' ? '记录' : '药品';
    return '吃药提醒';
  }

  function updateTopbar() {
    var backBtn = document.getElementById('backBtn');
    var gearBtn = document.getElementById('gearBtn');
    var titleEl = document.getElementById('topTitle');
    var dateEl = document.getElementById('dateLine');
    var inSettings = state.view !== 'main';
    if (backBtn) backBtn.hidden = !inSettings;
    if (gearBtn) gearBtn.hidden = inSettings;
    if (titleEl) titleEl.textContent = viewTitle();
    if (dateEl) {
      if (inSettings) {
        dateEl.hidden = true;
      } else {
        dateEl.hidden = false;
        dateEl.textContent = (state.today && state.today.dateLabel)
          ? state.today.dateLabel + ' · 本地提醒'
          : '本地提醒 · 不联网';
      }
    }
  }

  function renderTabbar() {
    var bar = document.getElementById('tabbar');
    if (!bar) return;
    var inSettings = state.view !== 'main';
    document.body.classList.toggle('no-tabbar', inSettings);
    if (inSettings) {
      bar.hidden = true;
      bar.innerHTML = '';
      return;
    }
    var items = uiMode() === 'care'
      ? [{ tab: 'today', mark: '今', label: '今天' }]
      : [
        { tab: 'today', mark: '今', label: '今天' },
        { tab: 'history', mark: '记', label: '记录' },
        { tab: 'meds', mark: '药', label: '药品' }
      ];
    bar.hidden = false;
    bar.style.gridTemplateColumns = 'repeat(' + items.length + ', 1fr)';
    bar.innerHTML = items.map(function (item) {
      var current = item.tab === state.tab ? ' aria-current="page"' : '';
      return '<button type="button" data-tab="' + item.tab + '"' + current + '>' +
        '<span class="tab-mark" aria-hidden="true">' + item.mark + '</span>' +
        esc(item.label) + '</button>';
    }).join('');
  }

  function render() {
    updateTopbar();
    renderTabbar();

    if (state.error) {
      main.innerHTML = '<section class="card empty"><div class="empty-title">读取失败</div>' +
        '<p class="empty-text">' + esc(state.error) + '</p>' +
        '<button class="btn btn-secondary" data-action="refresh-view">重试</button></section>';
      return;
    }
    if (!state.today) {
      main.innerHTML = '<div class="loading">正在读取…</div>';
      return;
    }

    if (state.view === 'settings') { main.innerHTML = viewSettings(); return; }
    if (state.view === 'settings-status') { main.innerHTML = viewSettingsStatus(); return; }
    if (state.view === 'settings-debug') { main.innerHTML = viewSettingsDebug(); return; }
    if (state.view === 'settings-about') { main.innerHTML = viewSettingsAbout(); return; }
    if (state.view === 'sub') {
      main.innerHTML = state.subTab === 'history' ? viewHistory() : viewMeds();
      return;
    }

    if (state.tab === 'today') main.innerHTML = viewToday();
    else if (state.tab === 'history') main.innerHTML = viewHistory();
    else main.innerHTML = viewMeds();
  }

  function banner(text, label, action, good) {
    return '<div class="banner ' + (good ? 'good' : '') + '">' +
      '<span class="banner-text">' + esc(text) + '</span>' +
      (label ? '<button type="button" data-action="' + action + '">' + esc(label) + '</button>' : '') +
      '</div>';
  }

  function statusBanners() {
    var s = state.status || {};
    var html = '';
    if (s.notificationsEnabled === false) {
      html += banner('通知权限没开，可能收不到提醒。', '去开启', 'open-notification-settings');
    }
    if (s.accessibilityEnabled !== true) {
      html += banner('锁屏卡片没开，锁屏时看不到提醒。', '去开启', 'open-accessibility-settings');
    } else if (s.accessibilityConnected !== true) {
      html += banner('锁屏卡片已开，但服务没连上。按 Home 回桌面，再打开 App。', '重试', 'refresh-view');
    }
    return html;
  }

  function undoBanner(today) {
    var undo = today && today.undo;
    if (!undo || !undo.available) return '';
    return '<div class="banner undo">' +
      '<span class="banner-text">刚刚记下了「' + esc(undo.label) + '」，10 分钟内可以撤销。</span>' +
      '<button type="button" data-action="undo-last">撤销</button></div>';
  }

  function medRow(med, stageKey) {
    var confirmed = !!med.confirmed;
    var photo = '';
    if (state.plan && state.plan.photoMode === 'home' && med.photo) {
      var data = getPhotoData(med.id);
      if (data) photo = '<img class="med-photo" src="' + data + '" alt="">';
    }
    return '<button type="button" class="med-row ' + (confirmed ? 'confirmed' : '') + '"' +
      ' data-action="toggle-med" data-stage="' + esc(stageKey) + '"' +
      ' data-med="' + esc(med.id) + '" aria-pressed="' + confirmed + '">' +
      photo +
      '<span class="med-check" aria-hidden="true">' + (confirmed ? '✓' : '') + '</span>' +
      '<span class="med-main"><span class="med-name">' + esc(med.name) + '</span>' +
      '<span class="med-amount">' + esc(med.amount) + '</span></span></button>';
  }

  function schedule(today) {
    var stages = today.stages || [];
    var rows = stages.map(function (stage) {
      var meds = (stage.medicines || []).map(function (med) { return med.name; }).join('、');
      var right = stage.status === 'done' && stage.at
        ? formatTime(stage.at)
        : stage.statusLabel;
      return '<div class="schedule-row">' +
        '<span class="status-dot ' + esc(stage.status) + '"></span>' +
        '<div class="schedule-main">' +
        '<div class="schedule-title ' + (stage.status === 'current' ? 'current' : '') + '">' +
        esc(stage.title) + '</div>' +
        '<div class="schedule-sub">' + esc(meds || '没有药') + '</div></div>' +
        '<div class="schedule-status ' + esc(stage.status) + '">' + esc(right) + '</div></div>';
    }).join('');

    return '<section class="card"><div class="card-head"><h2>今天的安排</h2>' +
      '<span class="small muted">完成 ' + (today.counts ? today.counts.done : 0) +
      ' / ' + (today.counts ? today.counts.total : 0) +
      (today.counts && today.counts.skipped ? ' · 跳过 ' + today.counts.skipped : '') +
      '</span></div>' +
      '<div class="schedule">' + rows + '</div></section>';
  }

  function viewToday() {
    var t = state.today;
    var html = statusBanners() + undoBanner(t);

    if (!t.hasMeds) {
      return html + '<section class="card empty">' +
        '<div class="empty-title">还没有添加药品</div>' +
        '<p class="empty-text">先让家里人把每天要吃的药加进来。<br>只需要选哪一顿、饭前还是饭后，不用填时间。</p>' +
        '<button class="btn btn-primary btn-lg" data-action="add-med">添加第一种药</button>' +
        '</section>';
    }

    if (t.finished || !t.current) {
      var skippedCount = t.counts ? t.counts.skipped : 0;
      return html + '<section class="card done-card">' +
        '<div class="done-mark">✓</div>' +
        '<div class="done-title">' + (skippedCount > 0 ? '今天的安排完成了' : '今天的药都吃完了') + '</div>' +
        '<p class="empty-text">' + (skippedCount > 0
          ? '其中有 ' + skippedCount + ' 项标记为跳过。明天凌晨 4 点后会重新开始。'
          : '可以休息了。明天凌晨 4 点后会重新开始。') + '</p>' +
        '</section>' + schedule(t);
    }

    var c = t.current;
    var rows = (c.medicines || []).map(function (med) {
      return medRow(med, c.key);
    }).join('');

    return html + '<section class="card stage-card">' +
      '<div class="kicker">' + esc(t.dateLabel) + ' · 第 ' + c.mealIndex + ' / ' + t.mealCount + ' 顿</div>' +
      '<h1 class="stage-title">' + esc(c.title) + '</h1>' +
      '<p class="stage-sub">' + esc(c.subtitle) + '</p>' +
      '<div class="meds">' + rows + '</div>' +
      '<button class="btn btn-primary btn-lg btn-block" data-action="confirm-all">' +
      esc(c.confirmLabel) + '</button>' +
      '<button class="btn-text skip-link" data-action="skip-stage">这一顿不吃了</button>' +
      '<p class="small muted center">' +
      (c.remaining > 1 ? '点药名可以单独确认，全部确认后自动进入下一项' : '点上面的大按钮确认') +
      '</p></section>' + schedule(t);
  }

  function historyStage(stage) {
    var meds = (stage.medicines || []).map(function (med) {
      return '<span class="hist-med ' + (med.confirmed ? 'ok' : '') + '">' +
        esc(med.name) + ' ' + esc(med.amount) + '</span>';
    }).join('');
    var right;
    if (stage.status === 'done') right = stage.at ? formatTime(stage.at) : '已完成';
    else if (stage.status === 'skipped') right = '已跳过';
    else if (stage.status === 'partial') right = '没吃完';
    else if (stage.status === 'current') right = '现在吃';
    else if (stage.status === 'upcoming') right = '还没到';
    else right = '未确认';

    return '<div class="schedule-row">' +
      '<span class="status-dot ' + esc(stage.status) + '"></span>' +
      '<div class="schedule-main">' +
      '<div class="schedule-title ' + (stage.status === 'current' ? 'current' : '') + '">' +
      esc(stage.title) + '</div>' +
      '<div class="hist-meds">' + meds + '</div></div>' +
      '<div class="schedule-status ' + esc(stage.status) + '">' + esc(right) + '</div></div>';
  }

  function dayCounts(day) {
    var bits = [];
    if (day.done) bits.push('完成 ' + day.done);
    if (day.skipped) bits.push('跳过 ' + day.skipped);
    if (day.partial) bits.push('没吃完 ' + day.partial);
    if (day.missed) bits.push('未确认 ' + day.missed);
    return bits.join(' · ');
  }

  function viewHistory() {
    var html = '';
    var t = state.today;
    if (t && t.hasMeds && t.stages && t.stages.length) {
      html += '<section class="card"><div class="card-head"><h2>今天</h2>' +
        '<span class="hist-counts">' + dayCounts(t.counts || {}) + '</span></div>' +
        '<div class="schedule">' + (t.stages || []).map(historyStage).join('') + '</div></section>';
    }

    var days = (state.history && state.history.days) || [];
    if (!days.length) {
      html += '<section class="card empty"><div class="empty-title">还没有历史记录</div>' +
        '<p class="empty-text">过了凌晨 4 点，今天会变成一条记录。</p></section>';
      return html;
    }

    html += '<p class="small muted">最近 ' + days.length + ' 天</p>';
    days.forEach(function (day) {
      html += '<section class="card"><div class="hist-day-head">' +
        '<span class="hist-date">' + esc(day.dateLabel || day.date) + '</span>' +
        '<span class="hist-counts">' + esc(dayCounts(day)) + '</span></div>' +
        '<div class="schedule">' + (day.stages || []).map(historyStage).join('') + '</div></section>';
    });
    return html;
  }

  function medMealIds(med) {
    if (Array.isArray(med.mealIds) && med.mealIds.length) return med.mealIds;
    return med.mealId ? [med.mealId] : [];
  }

  function medCard(med) {
    var mealIds = medMealIds(med);
    var frequency = mealIds.length > 1 ? ' · 每天 ' + mealIds.length + ' 次' : '';
    var photo = '';
    if (state.plan && state.plan.photoMode && state.plan.photoMode !== 'off' && med.photo) {
      var data = getPhotoData(med.id);
      if (data) photo = '<img class="med-thumb" src="' + data + '" alt="">';
    }
    return '<button type="button" class="med-item" data-action="edit-med" data-id="' + esc(med.id) + '">' +
      photo +
      '<span class="med-item-main"><span class="med-item-name">' + esc(med.name) + '</span>' +
      '<span class="small muted">一次 ' + esc(med.amount) + frequency + '</span></span>' +
      '<span class="chev" aria-hidden="true">›</span></button>';
  }

  function viewMeds() {
    var plan = state.plan || {};
    var meds = plan.medicines || [];
    var meals = plan.meals || [];
    var html = '<div class="page-head"><h1>药品</h1>' +
      '<button type="button" class="btn btn-primary" data-action="add-med">＋ 添加</button></div>';

    if (!meds.length) {
      return html + '<section class="card empty"><div class="empty-title">还没有药品</div>' +
        '<p class="empty-text">添加时只需填药名、一次吃多少，再选哪一顿、饭前还是饭后。</p>' +
        '<button class="btn btn-primary btn-lg" data-action="add-med">添加第一种药</button></section>';
    }

    meals.forEach(function (meal) {
      var list = meds.filter(function (med) {
        return medMealIds(med).indexOf(meal.id) >= 0;
      });
      if (!list.length) return;
      html += '<section class="card"><h2>' + esc(meal.label) + '</h2>';
      [{ key: 'before', label: '饭前' }, { key: 'after', label: '饭后' }].forEach(function (group) {
        var items = list.filter(function (med) { return (med.timing || 'before') === group.key; });
        if (!items.length) return;
        html += '<div class="group-label">' + group.label + '</div>';
        items.forEach(function (med) { html += medCard(med); });
      });
      html += '</section>';
    });

    var known = {};
    meals.forEach(function (meal) { known[meal.id] = true; });
    var orphan = meds.filter(function (med) {
      return !medMealIds(med).some(function (id) { return known[id]; });
    });
    if (orphan.length) {
      html += '<section class="card"><h2>未安排</h2>';
      orphan.forEach(function (med) { html += medCard(med); });
      html += '</section>';
    }

    html += '<button type="button" class="btn btn-secondary btn-block" data-action="add-med">＋ 再添加一种</button>';
    return html;
  }

  function statusRow(title, sub, ok, action, actionLabel, pillText) {
    var pill = pillText || (ok ? '已开' : '未开');
    return '<div class="status-row">' +
      '<span class="status-pill ' + (ok ? 'ok' : 'warn') + '">' + esc(pill) + '</span>' +
      '<div class="status-row-main"><div class="status-row-title">' + esc(title) + '</div>' +
      '<div class="status-row-sub">' + esc(sub) + '</div></div>' +
      '<button type="button" data-action="' + action + '">' + esc(actionLabel) + '</button></div>';
  }

  function pendingRow(title, sub, action, actionLabel) {
    return '<div class="status-row">' +
      '<span class="status-pill warn">待处理</span>' +
      '<div class="status-row-main"><div class="status-row-title">' + esc(title) + '</div>' +
      '<div class="status-row-sub">' + esc(sub) + '</div></div>' +
      '<button type="button" data-action="' + action + '">' + esc(actionLabel) + '</button></div>';
  }

  function navRow(title, sub, action) {
    return '<button type="button" class="nav-row" data-action="' + action + '">' +
      '<span class="nav-main"><span class="nav-title">' + esc(title) + '</span>' +
      (sub ? '<span class="small muted">' + esc(sub) + '</span>' : '') + '</span>' +
      '<span class="chev" aria-hidden="true">›</span></button>';
  }

  function pendingItems() {
    var s = state.status || {};
    var html = '';
    if (s.notificationsEnabled !== true) {
      html += pendingRow('通知权限没开', '收不到吃药提醒', 'open-notification-settings', '去开启');
    }
    if (s.accessibilityEnabled !== true) {
      html += pendingRow('锁屏卡片没开', '锁屏时看不到提醒', 'open-accessibility-settings', '去开启');
    } else if (s.accessibilityConnected !== true) {
      html += pendingRow('锁屏卡片没连上', '按 Home 回桌面，再打开 App', 'refresh-view', '重试');
    }
    return html;
  }

  function viewSettings() {
    var plan = state.plan || {};
    var status = state.status || {};
    var mode = plan.uiMode === 'care' ? 'care' : 'normal';
    var photoMode = plan.photoMode || 'off';
    var mealCount = (plan.meals || []).length || 3;
    var scale = Number(plan.fontScale) || 1;

    var html = '<h1 class="page-title">设置</h1>';

    var pending = pendingItems();
    if (pending) {
      html += '<section class="card"><h2>待处理</h2>' + pending +
        '<p class="small muted">设置好后这里会自动收起，不用再进设置找。</p></section>';
    }

    html += '<section class="card"><h2>界面模式</h2><div class="seg">' +
      [['normal', '正常版'], ['care', '长辈版']].map(function (item) {
        return '<button type="button" class="' + (mode === item[0] ? 'selected' : '') +
          '" data-action="ui-mode" data-mode="' + item[0] + '">' + item[1] + '</button>';
      }).join('') + '</div>' +
      '<p class="small muted">长辈版：更大更清楚的字和按钮，隐藏「记录、药品」标签，' +
      '这两项移到本页下方。</p></section>';

    html += '<section class="card"><h2>常用</h2>' +
      '<div class="group-label">字号</div><div class="seg">' +
      [[1, '标准'], [1.3, '大'], [1.6, '特大']].map(function (item) {
        return '<button type="button" class="' + (Math.abs(scale - item[0]) < 0.01 ? 'selected' : '') +
          '" data-action="font-scale" data-scale="' + item[0] + '">' + item[1] + '</button>';
      }).join('') + '</div>' +
      '<div class="group-label">一天几顿</div><div class="seg">' +
      [2, 3, 4].map(function (n) {
        return '<button type="button" class="' + (mealCount === n ? 'selected' : '') +
          '" data-action="meal-count" data-count="' + n + '">' + n + ' 顿</button>';
      }).join('') + '</div>' +
      '<p class="small muted">默认 3 顿。减餐次时，要先把被取消那一顿的药改掉；' +
      '日界线是凌晨 4 点。</p></section>';

    html += '<section class="card">' +
      navRow('提醒状态', '通知、锁屏卡片', 'open-settings-status');

    if (mode === 'care') {
      html += navRow('药品管理', '添加、修改、删除药品', 'open-sub-meds') +
        navRow('记录', '今天和最近 14 天', 'open-sub-history');
    }
    html += '</section>';

    html += '<section class="card"><h2>药品照片</h2><div class="seg">' +
      [['off', '关闭'], ['list', '列表'], ['home', '列表 + 首页']].map(function (item) {
        return '<button type="button" class="' + (photoMode === item[0] ? 'selected' : '') +
          '" data-action="photo-mode" data-mode="' + item[0] + '">' + item[1] + '</button>';
      }).join('') + '</div>' +
      '<p class="small muted">照片只做辅助识别，不替代药名文字；没照片时不显示占位图。</p></section>';

    html += '<section class="card">' +
      navRow('调试', '备份、测试、重新开始今天', 'open-settings-debug') +
      navRow('关于', '版本 ' + (status.versionName || ''), 'open-settings-about') +
      '</section>';

    return html;
  }

  function viewSettingsStatus() {
    var s = state.status || {};
    var notificationsOk = s.notificationsEnabled === true;
    var accessibilityOk = s.accessibilityEnabled === true && s.accessibilityConnected === true;
    var accessibilitySub = s.accessibilityEnabled !== true
      ? '没开。锁屏上看不到吃药卡片。'
      : (s.accessibilityConnected === true
        ? '已连接。锁屏时会显示吃药卡片。'
        : '已开，但服务没连上。回桌面再打开 App。');

    return '<h1 class="page-title">提醒状态</h1>' +
      '<section class="card">' +
      statusRow('通知', notificationsOk ? '通知权限已开。' : '通知权限没开，收不到提醒。',
        notificationsOk, 'open-notification-settings', '设置',
        notificationsOk ? '已开' : '未开') +
      statusRow('锁屏卡片', accessibilitySub, accessibilityOk,
        'open-accessibility-settings', '设置',
        accessibilityOk ? '已开' : (s.accessibilityEnabled === true ? '注意' : '未开')) +
      '<button type="button" class="btn-text" data-action="open-app-settings">开关是灰的？打开应用设置</button>' +
      '<p class="small muted">锁屏卡片需要无障碍权限。它只画卡片，不读取屏幕内容。</p>' +
      '</section>' +
      '<section class="card"><h2>检查</h2>' +
      '<button type="button" class="btn btn-secondary btn-block" data-action="test-notification">发一条测试通知</button>' +
      '<button type="button" class="btn btn-secondary btn-block" data-action="preview-card">预览锁屏卡片（8 秒）</button>' +
      '<p class="small muted">预览需要先开启锁屏卡片服务。</p></section>';
  }

  function viewSettingsDebug() {
    return '<h1 class="page-title">调试</h1>' +
      '<section class="card"><h2>数据</h2>' +
      '<button type="button" class="btn btn-secondary btn-block" data-action="export-backup">导出备份</button>' +
      '<button type="button" class="btn btn-secondary btn-block" data-action="import-backup">从备份恢复</button>' +
      '<p class="small muted">备份是一个 JSON 文件，保存在你选择的位置；恢复会覆盖当前药品和记录。</p>' +
      '<button type="button" class="btn btn-danger btn-block" data-action="reset-today">重新开始今天</button>' +
      '<p class="small muted">只清空今天的确认记录，不删除药品。</p></section>' +
      '<section class="card"><h2>测试</h2>' +
      '<button type="button" class="btn btn-secondary btn-block" data-action="open-lock-card">打开全屏大字卡片（备用）</button>' +
      '</section>';
  }

  function viewSettingsAbout() {
    var s = state.status || {};
    return '<h1 class="page-title">关于</h1>' +
      '<section class="card">' +
      '<p><strong>吃药提醒</strong> ' + esc(s.versionName || '') + '</p>' +
      '<p class="small muted">完全离线，不联网，不上传。所有数据保存在本机应用私有目录。</p>' +
      '<p class="small muted">本应用只负责提醒和记录，用药方案请遵医嘱。</p>' +
      '<p class="small muted">设置图标来自 Lucide（ISC 许可）。</p>' +
      '<p class="small muted">开源地址：暂未发布。</p>' +
      '</section>';
  }

  function metaRow(title, value) {
    return '<div class="status-row"><div class="status-row-main">' +
      '<div class="status-row-title">' + esc(title) + '</div>' +
      '<div class="status-row-sub">' + esc(value) + '</div></div></div>';
  }

  function openImportSheet(summary) {
    var exportedAt = summary && summary.exportedAt ? String(summary.exportedAt) : '';
    if (exportedAt.length >= 16) {
      exportedAt = exportedAt.substring(0, 10) + ' ' + exportedAt.substring(11, 16);
    }
    var html = '<div class="sheet-head">' +
      '<button type="button" class="btn-text" data-action="cancel-import">取消</button>' +
      '<div class="sheet-title">恢复备份</div>' +
      '<span class="spacer"></span></div>' +
      '<section class="card"><h2>这份备份包含</h2>' +
      metaRow('导出时间', exportedAt || '未知') +
      metaRow('药品', (summary.medicineCount || 0) + ' 种 · ' +
        (summary.mealCount || 0) + ' 顿') +
      metaRow('历史记录', (summary.dayCount || 0) + ' 天 · ' +
        (summary.eventCount || 0) + ' 条') +
      '<p class="small muted">恢复会覆盖当前的药品、今天的确认和全部历史记录。' +
      '想留底可以先取消，回设置里导出现在的数据。</p>' +
      '<button type="button" class="btn btn-primary btn-lg btn-block" ' +
      'data-action="apply-import">确认恢复</button>' +
      '<button type="button" class="btn btn-secondary btn-block" ' +
      'data-action="cancel-import">取消</button></section>';
    openSheet(html);
  }

  function openSheet(html) {
    sheet.innerHTML = html;
    sheet.classList.add('open');
    document.body.classList.add('sheet-open');
    state.sheetOpen = true;
    if (!history.state || !history.state.sheet) {
      try { history.pushState({ sheet: true }, ''); } catch (ignored) {}
    }
  }

  function closeSheet(fromPop) {
    if (!state.sheetOpen) return;
    state.sheetOpen = false;
    state.editingId = '';
    sheet.classList.remove('open');
    sheet.innerHTML = '';
    document.body.classList.remove('sheet-open');
    if (!fromPop && history.state && history.state.sheet) {
      try { history.back(); } catch (ignored) {}
    }
  }

  function openMedSheet(id) {
    state.editingOrigin = (state.view === 'sub' || state.tab === 'meds') ? 'meds' : 'today';
    var plan = state.plan;
    if (!plan) {
      plan = api('getPlan');
      state.plan = plan;
    }
    var meds = plan.medicines || [];
    var meals = plan.meals || [];
    var med = null;
    if (id) {
      meds.forEach(function (item) { if (item.id === id) med = item; });
      if (!med) {
        showToast('这条药已经不存在了');
        return;
      }
    }
    state.editingId = med ? med.id : '';
    var selectedMealIds = med ? medMealIds(med) : (meals[0] ? [meals[0].id] : []);
    var timing = med ? (med.timing || 'before') : 'before';

    var html = '<div class="sheet-head">' +
      '<button type="button" class="btn-text" data-action="close-sheet">取消</button>' +
      '<div class="sheet-title">' + (med ? '修改药品' : '添加药品') + '</div>' +
      '<span class="spacer"></span></div>' +
      '<form id="medForm" novalidate>' +
      '<label class="field"><span>药名</span>' +
      '<input id="medName" type="text" maxlength="30" autocomplete="off" ' +
      'placeholder="例如：阿司匹林" value="' + esc(med ? med.name : '') + '"></label>' +
      '<label class="field"><span>一次吃多少</span>' +
      '<input id="medAmount" type="text" maxlength="20" autocomplete="off" ' +
      'placeholder="例如：1 片" value="' + esc(med ? med.amount : '') + '"></label>' +
      '<div class="field"><span>药品照片（可选）</span>' +
      '<div class="photo-box" id="photoBox"></div></div>' +
      '<div class="field"><span>哪几顿（可以多选）</span><div class="seg" id="mealSeg">' +
      meals.map(function (meal) {
        return '<button type="button" data-meal="' + esc(meal.id) + '" class="' +
          (selectedMealIds.indexOf(meal.id) >= 0 ? 'selected' : '') + '">' + esc(meal.label) + '</button>';
      }).join('') + '</div></div>' +
      '<div class="field"><span>饭前还是饭后</span><div class="seg" id="timingSeg">' +
      '<button type="button" data-timing="before" class="' + (timing === 'before' ? 'selected' : '') + '">饭前</button>' +
      '<button type="button" data-timing="after" class="' + (timing === 'after' ? 'selected' : '') + '">饭后</button>' +
      '</div></div>' +
      '<p class="small muted">同一种药可以勾选多顿，例如一天 3 次就勾第 1、2、3 顿。只支持每天吃；按固定时间吃的药以后再加。</p>' +
      '<button type="submit" class="btn btn-primary btn-lg btn-block">保存</button>' +
      (med ? '<button type="button" class="btn btn-danger btn-block" data-action="delete-med" data-id="' +
        esc(med.id) + '">删除这条药</button>' : '') +
      '</form>';
    openSheet(html);
    renderPhotoBox();
    setTimeout(function () {
      var input = document.getElementById('medName');
      if (input) input.focus();
    }, 60);
  }

  function saveMed() {
    var nameEl = document.getElementById('medName');
    var amountEl = document.getElementById('medAmount');
    var mealEls = Array.prototype.slice.call(document.querySelectorAll('#mealSeg .selected'));
    var timingEl = document.querySelector('#timingSeg .selected');
    if (!nameEl || !amountEl || !timingEl) return;

    var name = nameEl.value.trim();
    var amount = amountEl.value.trim();
    if (!name) {
      showToast('请填写药名');
      nameEl.focus();
      return;
    }
    if (!amount) {
      showToast('请填写一次吃多少');
      amountEl.focus();
      return;
    }
    if (!mealEls.length) {
      showToast('请选择哪几顿吃');
      return;
    }

    var mealIds = mealEls.map(function (el) { return el.getAttribute('data-meal'); });
    var payload = {
      id: state.editingId || '',
      name: name,
      amount: amount,
      mealIds: mealIds,
      timing: timingEl.getAttribute('data-timing')
    };
    var result = api('saveMedicine', JSON.stringify(payload));
    if (!result.ok) {
      showToast(result.error || '保存失败');
      return;
    }
    closeSheet(false);
    try {
      if (state.editingOrigin === 'today') {
        state.view = 'main';
        state.tab = 'today';
        loadView();
        render();
      } else {
        returnToMeds();
      }
    } catch (error) {
      showToast(String(error));
    }
    state.editingOrigin = '';
    showToast('已保存');
  }

  async function handleAction(action, el) {
    if (action === 'toggle-med') {
      var stageKey = el.getAttribute('data-stage');
      var medId = el.getAttribute('data-med');
      var confirmed = el.getAttribute('aria-pressed') === 'true';
      var result = confirmed
        ? api('unconfirmMed', stageKey, medId)
        : api('confirmMed', stageKey, medId);
      if (result && result.ok) {
        state.today = result;
        render();
      }
      return;
    }

    if (action === 'confirm-all') {
      var today = api('confirmAll');
      if (today && today.ok) {
        state.today = today;
        render();
        showToast(today.finished ? '今天的药都吃完了 ✓' : '已记下，下一项已更新');
      }
      return;
    }

    if (action === 'skip-stage') {
      var skipOk = await confirmDialog({
        title: '这一顿不吃了？',
        message: '会记为「已跳过」，直接进入下一项。',
        confirmLabel: '这一顿不吃了'
      });
      if (!skipOk) return;
      var skipped = api('skipStage');
      if (skipped && skipped.ok) {
        state.today = skipped;
        render();
        showToast('已跳过这一项');
      }
      return;
    }

    if (action === 'reset-today') {
      var resetOk = await confirmDialog({
        title: '重新开始今天？',
        message: '今天的确认记录会清空，药品不会删除。',
        confirmLabel: '重新开始',
        danger: true
      });
      if (!resetOk) return;
      var reset = api('resetToday');
      if (reset && reset.ok) {
        state.today = reset;
        loadView();
        render();
        showToast('今天已重新开始');
      }
      return;
    }

    if (action === 'undo-last') {
      var undone = api('undoLast');
      if (undone && undone.ok) {
        state.today = undone;
        render();
        showToast('已撤销上一次');
      } else {
        showToast(undone && undone.error ? undone.error : '撤销失败');
      }
      return;
    }

    if (action === 'export-backup') {
      var exportResult = api('exportBackup');
      showToast(exportResult.ok ? '请选择保存位置' : (exportResult.error || '无法导出'));
      return;
    }

    if (action === 'import-backup') {
      var importResult = api('importBackup');
      showToast(importResult.ok ? '请选择备份文件' : (importResult.error || '无法导入'));
      return;
    }

    if (action === 'cancel-import') {
      api('cancelImport');
      closeSheet(false);
      showToast('已取消恢复');
      return;
    }

    if (action === 'apply-import') {
      var applied = api('applyImport');
      if (applied && applied.ok) {
        closeSheet(false);
        state.view = 'main';
        state.tab = 'today';
        try {
          loadView();
          render();
        } catch (ignored) {}
        showToast('已恢复备份');
      } else {
        showToast(applied && applied.error ? applied.error : '恢复失败');
      }
      return;
    }

    if (action === 'go-meds') {
      switchTab('meds');
      return;
    }

    if (action === 'open-sub-meds') {
      openSub('meds');
      return;
    }

    if (action === 'open-sub-history') {
      openSub('history');
      return;
    }

    if (action === 'go-history') {
      switchTab('history');
      return;
    }

    if (action === 'open-settings') {
      openView('settings');
      return;
    }

    if (action === 'settings-back') {
      settingsBack();
      return;
    }

    if (action === 'open-settings-status') {
      openView('settings-status');
      return;
    }

    if (action === 'open-settings-debug') {
      openView('settings-debug');
      return;
    }

    if (action === 'open-settings-about') {
      openView('settings-about');
      return;
    }

    if (action === 'ui-mode') {
      var mode = el.getAttribute('data-mode') === 'care' ? 'care' : 'normal';
      var modeResult = api('setUiMode', mode);
      if (!modeResult.ok) {
        showToast(modeResult.error || '设置失败');
        return;
      }
      if (mode === 'care') {
        var currentScale = Number(state.plan && state.plan.fontScale) || 1;
        if (currentScale < 1.6) api('setFontScale', '1.6');
        if (state.tab !== 'today') state.tab = 'today';
      }
      loadView();
      render();
      showToast(mode === 'care' ? '已切换到长辈版' : '已切换到正常版');
      return;
    }

    if (action === 'photo-mode') {
      var photo = el.getAttribute('data-mode');
      var photoResult = api('setPhotoMode', photo);
      if (!photoResult.ok) {
        showToast(photoResult.error || '设置失败');
        return;
      }
      state.plan = api('getPlan');
      render();
      showToast('照片显示已更新');
      return;
    }

    if (action === 'pick-photo') {
      if (!state.editingId) {
        showToast('先保存这条药，再添加照片');
        return;
      }
      var pick = api('pickPhoto', state.editingId);
      showToast(pick && pick.ok ? '请选择照片' : (pick && pick.error ? pick.error : '无法打开照片选择器'));
      return;
    }

    if (action === 'remove-photo') {
      var removed = api('removePhoto', state.editingId);
      if (removed && removed.ok) {
        clearPhotoCache(state.editingId);
        renderPhotoBox();
        showToast('照片已删除');
      } else {
        showToast(removed && removed.error ? removed.error : '删除失败');
      }
      return;
    }

    if (action === 'add-med') {
      openMedSheet('');
      return;
    }

    if (action === 'edit-med') {
      openMedSheet(el.getAttribute('data-id'));
      return;
    }

    if (action === 'close-sheet') {
      closeSheet(false);
      return;
    }

    if (action === 'delete-med') {
      var id = el.getAttribute('data-id');
      var nameInput = document.getElementById('medName');
      var medName = nameInput && nameInput.value ? nameInput.value.trim() : '';
      var deleteOk = await confirmDialog({
        title: medName ? '删除「' + medName + '」？' : '删除这条药？',
        message: '删除后不再出现在任何餐次。',
        confirmLabel: '删除',
        danger: true
      });
      if (!deleteOk) return;
      var deleted = api('deleteMedicine', id);
      if (!deleted.ok) {
        showToast(deleted.error || '删除失败');
        return;
      }
      closeSheet(false);
      try {
        returnToMeds();
      } catch (error) {
        showToast(String(error));
      }
      showToast('已删除');
      return;
    }

    if (action === 'meal-count') {
      var count = Number(el.getAttribute('data-count'));
      if (state.plan && (state.plan.meals || []).length === count) return;
      var countOk = await confirmDialog({
        title: '改成一天 ' + count + ' 顿？',
        message: '如果被取消的那一顿还有药，需要先改到别的顿。',
        confirmLabel: '改成 ' + count + ' 顿'
      });
      if (!countOk) return;
      var countResult = api('setMealCount', count);
      if (!countResult.ok) {
        showToast(countResult.error || '修改失败');
        return;
      }
      state.plan = api('getPlan');
      state.today = api('getToday');
      render();
      showToast('已改成一天 ' + count + ' 顿');
      return;
    }

    if (action === 'font-scale') {
      var scale = el.getAttribute('data-scale');
      var fontResult = api('setFontScale', scale);
      if (fontResult.ok) {
        applyFont(fontResult.fontScale || scale);
        if (state.plan) state.plan.fontScale = state.fontScale;
        render();
      }
      return;
    }

    if (action === 'test-notification') {
      var test = api('sendTestNotification');
      showToast(test.ok ? '已发送，下拉通知栏看看' : (test.error || '发送失败'));
      return;
    }

    if (action === 'preview-card') {
      var preview = api('previewLockCard');
      showToast(preview.ok ? '已显示 8 秒，请看屏幕' : (preview.error || '预览失败'));
      return;
    }

    if (action === 'refresh-view') {
      reload();
      return;
    }

    if (action === 'open-notification-settings') { api('openNotificationSettings'); return; }
    if (action === 'open-accessibility-settings') { api('openAccessibilitySettings'); return; }
    if (action === 'open-app-settings') { api('openAppSettings'); return; }
    if (action === 'open-lock-card') { api('openLockScreenCard'); return; }
  }

  document.addEventListener('click', async function (event) {
    var mealButton = event.target.closest('[data-meal]');
    if (mealButton) {
      mealButton.classList.toggle('selected');
      return;
    }
    var timingButton = event.target.closest('[data-timing]');
    if (timingButton) {
      Array.prototype.forEach.call(document.querySelectorAll('#timingSeg [data-timing]'), function (btn) {
        btn.classList.toggle('selected', btn === timingButton);
      });
      return;
    }

    var tabButton = event.target.closest('[data-tab]');
    if (tabButton && !state.sheetOpen) {
      switchTab(tabButton.getAttribute('data-tab'));
      return;
    }

    var actionButton = event.target.closest('[data-action]');
    if (!actionButton) return;
    event.preventDefault();
    try {
      await handleAction(actionButton.getAttribute('data-action'), actionButton);
    } catch (error) {
      showToast(String(error && error.message ? error.message : error));
    }
  });

  document.addEventListener('submit', function (event) {
    if (event.target && event.target.id === 'medForm') {
      event.preventDefault();
      try { saveMed(); } catch (error) { showToast(String(error)); }
    }
  });

  window.addEventListener('popstate', function () {
    if (state.sheetOpen) closeSheet(true);
  });

  window.MedAppRefresh = function () {
    if (state.sheetOpen) return;
    try {
      loadView();
      render();
    } catch (ignored) {}
  };

  window.MedAppToast = function (text) {
    showToast(String(text == null ? '' : text));
  };

  // 硬件返回键：返回 true 表示已处理（弹窗/弹层/设置层级），false 交给原生决定退出。
  window.MedAppHandleBack = function () {
    if (activeDialogCancel) {
      activeDialogCancel();
      return true;
    }
    if (state.sheetOpen) {
      closeSheet(false);
      return true;
    }
    if (state.view !== 'main') {
      settingsBack();
      return true;
    }
    return false;
  };

  window.MedAppBackupResult = function (result) {
    var ok = result && result.ok;
    showToast(result && result.message ? result.message : (ok ? '完成' : '操作失败'));
    if (!ok) return;
    try {
      loadView();
      render();
    } catch (ignored) {}
  };

  window.MedAppImportPreview = function (raw) {
    var summary = {};
    try {
      summary = JSON.parse(raw) || {};
    } catch (ignored) {}
    openImportSheet(summary);
  };

  window.MedAppPhotoResult = function (result) {
    var ok = result && result.ok;
    var medId = result && result.medId ? result.medId : '';
    showToast(result && result.message ? result.message : (ok ? '照片已保存' : '照片保存失败'));
    if (!ok) return;
    clearPhotoCache(medId);
    if (state.sheetOpen && state.editingId === medId) {
      renderPhotoBox();
    }
    try {
      state.plan = api('getPlan');
      render();
    } catch (ignored) {}
  };

  document.addEventListener('visibilitychange', function () {
    if (!document.hidden && !state.sheetOpen) window.MedAppRefresh();
  });

  if (!bridge) {
    main.innerHTML = '<section class="card empty"><div class="empty-title">请在 App 中打开</div>' +
      '<p class="empty-text">普通浏览器里只能看界面，不能读取或保存吃药数据。请安装 APK 后使用。</p></section>';
    return;
  }

  try {
    loadView();
    render();
  } catch (error) {
    state.error = String(error && error.message ? error.message : error);
    render();
  }
})();
