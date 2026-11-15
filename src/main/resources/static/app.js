'use strict';

/* ==================== 基础工具 ==================== */
const $ = (s, root = document) => root.querySelector(s);
const $$ = (s, root = document) => Array.from(root.querySelectorAll(s));
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, c =>
    ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
const money = (v) => '¥' + Number(v || 0).toLocaleString('zh-CN', {minimumFractionDigits: 2, maximumFractionDigits: 2});
const shortMoney = (v) => {
    const n = Number(v || 0);
    return n >= 10000 ? '¥' + (n / 10000).toFixed(1) + '万' : '¥' + n.toLocaleString('zh-CN', {maximumFractionDigits: 0});
};
const shortNum = (v) => {
    const n = Number(v || 0);
    return n >= 10000 ? (n / 10000).toFixed(1) + '万' : Math.round(n).toLocaleString('zh-CN');
};
const fmtTime = (v) => {
    if (!v) return '-';
    if (typeof v === 'number') return new Date(v).toLocaleString('zh-CN', {hour12: false});
    return String(v).replace('T', ' ').slice(0, 19);
};
// 单据状态字典：采购 / 销售 / 生产 / 凭证 / 往来单共用一套中文映射，
// 后端状态机新增状态时，只需在这里补一条展示规则。
const STATUS_MAP = {
    DRAFT: {t: '草稿', c: 'gray'}, CONFIRMED: {t: '已审核', c: 'blue'},
    RECEIVED: {t: '已入库', c: 'green'}, SHIPPED: {t: '已发货', c: 'green'},
    ISSUED: {t: '已领料', c: 'blue'}, FINISHED: {t: '已完工', c: 'green'},
    POSTED: {t: '已过账', c: 'green'}, VOID: {t: '已作废', c: 'red'},
    OPEN: {t: '未结清', c: 'orange'}, PARTIAL: {t: '部分结清', c: 'blue'},
    CLOSED: {t: '已结清', c: 'green'}, CANCELLED: {t: '已取消', c: 'red'}
};
const FLOW_MAP = {
    PURCHASE_IN: {t: '采购入库', c: 'green'}, SALE_OUT: {t: '销售出库', c: 'orange'},
    ADJUST: {t: '盘点调整', c: 'blue'},
    MATERIAL_ISSUE: {t: '生产领料', c: 'orange'}, PRODUCT_FINISH: {t: '完工入库', c: 'green'}
};
// 凭证来源类型 → 中文，便于在凭证列表看出「这张凭证是哪张业务单据自动生成的」
const VOUCHER_SOURCE_MAP = {
    MANUAL: '手工录入', PURCHASE_IN: '采购入库', SALE_OUT: '销售出库',
    SALE_COST: '结转成本', SETTLE: '收付款', MATERIAL_ISSUE: '生产领料',
    PRODUCT_FINISH: '完工入库', OPENING: '期初建账'
};
const badge = (v, map) => {
    const m = map[v] || {t: v || '-', c: 'gray'};
    return `<span class="badge ${m.c}">${esc(m.t)}</span>`;
};

let TOKEN = localStorage.getItem('erp_token') || '';
let USER = JSON.parse(localStorage.getItem('erp_user') || 'null');
// 菜单权限：['*'] 表示管理员全放行；其它角色是可见页面 key 的数组。
// 后端 AuthInterceptor 会独立校验接口权限，前端这份只用于「不显示无权菜单」。
let PERMISSIONS = JSON.parse(localStorage.getItem('erp_permissions') || '["*"]');
let OPTIONS = {warehouses: [], suppliers: [], customers: [], products: [], departments: [], employees: []};

/** 判断当前角色能否看到某个页面 */
function canSee(key) {
    return PERMISSIONS.includes('*') || PERMISSIONS.includes(key);
}

function toast(msg, type = 'ok') {
    const el = document.createElement('div');
    el.className = 'toast ' + type;
    el.textContent = msg;
    $('#toast').appendChild(el);
    setTimeout(() => el.remove(), 3200);
}

/* ==================== 请求封装 ==================== */
async function api(path, opt = {}) {
    const {method = 'GET', body, params} = opt;
    let url = '/api' + path;
    if (params) {
        const q = new URLSearchParams();
        Object.entries(params).forEach(([k, v]) => {
            if (v !== undefined && v !== null && v !== '') q.append(k, v);
        });
        const s = q.toString();
        if (s) url += '?' + s;
    }
    const res = await fetch(url, {
        method,
        headers: {'Content-Type': 'application/json', 'X-Token': TOKEN},
        body: body === undefined ? undefined : JSON.stringify(body)
    });
    let json;
    try {
        json = await res.json();
    } catch (e) {
        throw new Error('服务器返回异常（HTTP ' + res.status + '）');
    }
    if (json.code === 401) {
        forceLogout();
        throw new Error(json.msg);
    }
    if (json.code !== 0) throw new Error(json.msg || '请求失败');
    // 任何写操作都可能改动了基础资料，让浏览器本地的下拉缓存立即失效。
    // 后端 CacheEvictInterceptor 负责清服务端缓存，这里负责清浏览器缓存，两边对齐。
    if (method !== 'GET') invalidateOptions();
    return json.data;
}

/* ==================== Excel 导出 ==================== */
/*
 * 后端 /api/export/{type} 返回二进制 xlsx，不是统一 JSON 响应体，
 * 所以这里不走 api() 封装，单独用 fetch + Blob 触发浏览器下载。
 * 仍然带上 X-Token，导出同样受登录与角色鉴权保护。
 */
const EXPORT_TYPES = {
    purchase: 'purchase', sales: 'sales', stock: 'stock', flows: 'flows',
    vouchers: 'vouchers', arap: 'arap', product: 'products', logs: 'logs'
};

async function downloadExcel(type) {
    const res = await fetch('/api/export/' + encodeURIComponent(type), {
        headers: {'X-Token': TOKEN}
    });
    const ct = res.headers.get('Content-Type') || '';
    if (!res.ok || !ct.includes('spreadsheetml')) {
        // 鉴权失败 / 类型不支持时后端返回的是 JSON，取出 msg 给用户看
        let msg = '导出失败（HTTP ' + res.status + '）';
        try {
            const j = await res.json();
            msg = j.msg || msg;
        } catch (e) { /* 非 JSON 响应用默认提示 */ }
        throw new Error(msg);
    }
    // 中文文件名按 RFC 5987 编码在 filename* 里，需要解码回来
    const cd = res.headers.get('Content-Disposition') || '';
    const m = cd.match(/filename\*=UTF-8''([^;]+)/i);
    const filename = m ? decodeURIComponent(m[1]) : (type + '.xlsx');
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** 页面渲染完成后统一往工具栏追加「导出 Excel」按钮，避免每个页面重复写一遍 */
function mountExportButtonForPage(key) {
    const type = EXPORT_TYPES[key];
    if (!type) return;
    const bar = document.querySelector('#content .toolbar');
    if (!bar || bar.querySelector('[data-export]')) return;
    const btn = document.createElement('button');
    btn.className = 'btn';
    btn.dataset.export = type;
    btn.textContent = '导出 Excel';
    btn.title = '导出全部数据（上限 5 万行）';
    btn.onclick = async () => {
        btn.disabled = true;
        btn.textContent = '导出中…';
        try {
            await downloadExcel(type);
            toast('导出成功，请查看浏览器下载');
        } catch (e) {
            toast(e.message, 'err');
        } finally {
            btn.disabled = false;
            btn.textContent = '导出 Excel';
        }
    };
    // 放在主操作按钮左边：最右侧始终留给「+ 新建」这类主操作，视觉层级更清楚
    const primary = bar.querySelector('.btn.primary');
    if (primary) bar.insertBefore(btn, primary);
    else bar.appendChild(btn);
}

/* ==================== 登录 / 布局 ==================== */
function showLogin() {
    $('#loginView').classList.remove('hidden');
    $('#appView').classList.add('hidden');
}

function showApp() {
    $('#loginView').classList.add('hidden');
    $('#appView').classList.remove('hidden');
}

function forceLogout() {
    TOKEN = '';
    USER = null;
    localStorage.removeItem('erp_token');
    localStorage.removeItem('erp_user');
    showLogin();
}

$('#loginForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    $('#loginError').textContent = '';
    const btn = $('#loginBtn');
    btn.disabled = true;
    try {
        const d = await api('/auth/login', {
            method: 'POST',
            body: {username: $('#loginUser').value.trim(), password: $('#loginPass').value}
        });
        TOKEN = d.token;
        USER = {username: d.username, realName: d.realName, role: d.role};
        PERMISSIONS = d.permissions || ['*'];
        localStorage.setItem('erp_token', TOKEN);
        localStorage.setItem('erp_user', JSON.stringify(USER));
        localStorage.setItem('erp_permissions', JSON.stringify(PERMISSIONS));
        await enterApp();
    } catch (err) {
        $('#loginError').textContent = err.message;
    } finally {
        btn.disabled = false;
    }
});

$('#logoutBtn').onclick = async () => {
    try {
        await api('/auth/logout', {method: 'POST'});
    } catch (e) { /* 忽略 */ }
    forceLogout();
};

async function enterApp() {
    showApp();
    $('#userName').textContent = USER?.realName || USER?.username || '管理员';
    $('#userRole').textContent = USER?.role || 'ADMIN';
    $('#avatar').textContent = (USER?.realName || 'A').slice(0, 1);
    buildMenu();
    // 先用浏览器本地缓存把下拉数据填上（首屏秒开），再回源刷新一次。
    // 后端 /master/options 本身也有缓存，所以这一步通常只要几毫秒。
    readOptionsCache();
    await refreshOptions();
    navigate('dashboard');
}

(async function init() {
    if (TOKEN && USER) {
        try {
            // 刷新页面时重新拉取权限：管理员改动角色后，前端不会一直用旧的缓存菜单
            const me = await api('/auth/me');
            if (me.permissions) {
                PERMISSIONS = me.permissions;
                localStorage.setItem('erp_permissions', JSON.stringify(PERMISSIONS));
            }
            await enterApp();
            return;
        } catch (e) { /* 令牌失效，回到登录页 */ }
    }
    showLogin();
})();

/* ==================== 菜单与路由 ==================== */
// 菜单分组参照金蝶 K/3 的功能域划分：供应链 → 制造 → 财务 → 往来 → 基础资料 → 系统。
// 每个 key 同时是后端 RolePermission 里的「页面权限标识」。
const MENU = [
    {
        group: '经营概览', items: [
            {key: 'dashboard', icon: '▦', label: '经营看板'},
            {key: 'ai', icon: '✦', label: 'AI 经营分析'}
        ]
    },
    {
        group: '供应链管理', items: [
            {key: 'purchase', icon: '↙', label: '采购订单'},
            {key: 'sales', icon: '↗', label: '销售订单'},
            {key: 'stock', icon: '▤', label: '库存查询'},
            {key: 'flows', icon: '≡', label: '库存流水'}
        ]
    },
    {
        group: '生产制造', items: [
            {key: 'boms', icon: '⚙', label: 'BOM 物料清单'},
            {key: 'mo', icon: '⛭', label: '生产任务单'}
        ]
    },
    {
        group: '财务会计', items: [
            {key: 'vouchers', icon: '▣', label: '记账凭证'},
            {key: 'subjects', icon: '◇', label: '会计科目'},
            {key: 'ledger', icon: '▤', label: '科目余额 / 明细账'},
            {key: 'reports', icon: '▥', label: '财务报表'},
            {key: 'periods', icon: '◷', label: '期末结账'}
        ]
    },
    {
        group: '往来管理', items: [
            {key: 'arap', icon: '⇄', label: '应收应付'},
            {key: 'settlements', icon: '¥', label: '收付款记录'},
            {key: 'aging', icon: '◔', label: '账龄分析'}
        ]
    },
    {
        group: '基础资料', items: [
            {key: 'product', icon: '◈', label: '商品 / 物料'},
            {key: 'supplier', icon: '◇', label: '供应商管理'},
            {key: 'customer', icon: '◎', label: '客户管理'},
            {key: 'warehouse', icon: '▣', label: '仓库管理'},
            {key: 'department', icon: '▦', label: '部门管理'},
            {key: 'employee', icon: '☺', label: '职员管理'}
        ]
    },
    {
        group: '系统管理', items: [
            {key: 'users', icon: '♙', label: '用户与权限'},
            {key: 'logs', icon: '◷', label: '操作日志'},
            {key: 'ops', icon: '◉', label: '系统运维'}
        ]
    }
];

// 路由表：key → {标题、说明、渲染函数}。新增模块只需在 MENU 加一项、PAGES 加一项。
const PAGES = {
    dashboard: {title: '经营看板', desc: '实时掌握采购、销售与库存全局', render: renderDashboard},
    ai: {title: 'AI 经营分析', desc: '大模型解读实时业务数据，给出风险预警与行动建议', render: renderAi},
    purchase: {title: '采购订单', desc: '下单 → 审核 → 收货入库，同步生成应付单与凭证', render: () => renderOrders('purchase')},
    sales: {title: '销售订单', desc: '下单 → 审核 → 发货出库，同步生成应收单与凭证', render: () => renderOrders('sales')},
    stock: {title: '库存查询', desc: '各仓库实时库存与安全库存预警', render: renderStock},
    flows: {title: '库存流水', desc: '每一次出入库变更都有据可查', render: renderFlows},
    boms: {title: 'BOM 物料清单', desc: '定义产成品与子件的用量关系', render: renderBoms},
    mo: {title: '生产任务单', desc: '审核 → 按 BOM 领料 → 完工入库', render: renderMo},
    vouchers: {title: '记账凭证', desc: '手工凭证与业务自动生成凭证统一台账', render: renderVouchers},
    subjects: {title: '会计科目', desc: '维护科目体系、类别与余额方向', render: renderSubjects},
    ledger: {title: '科目余额 / 明细账', desc: '按期间查询余额表与逐笔明细', render: renderLedger},
    reports: {title: '财务报表', desc: '利润表与资产负债表，自动勾稽', render: renderReports},
    periods: {title: '期末结账', desc: '按会计期间控制凭证录入', render: renderPeriods},
    arap: {title: '应收应付', desc: '往来单据与收付款核销', render: renderArap},
    settlements: {title: '收付款记录', desc: '每一次收款 / 付款核销流水', render: renderSettlements},
    aging: {title: '账龄分析', desc: '按账龄区间评估往来款风险', render: renderAging},
    product: {title: '商品 / 物料', desc: '维护商品档案、价格与安全库存', render: () => renderMaster('product')},
    supplier: {title: '供应商管理', desc: '维护采购往来单位档案', render: () => renderMaster('supplier')},
    customer: {title: '客户管理', desc: '维护销售往来单位档案', render: () => renderMaster('customer')},
    warehouse: {title: '仓库管理', desc: '维护仓库档案与地址信息', render: () => renderMaster('warehouse')},
    department: {title: '部门管理', desc: '维护组织架构', render: () => renderMaster('department')},
    employee: {title: '职员管理', desc: '维护职员与所属部门', render: () => renderMaster('employee')},
    users: {title: '用户与权限', desc: '账号、角色与启停管理', render: renderUsers},
    logs: {title: '操作日志', desc: '记录系统内的关键业务操作', render: renderLogs},
    ops: {title: '系统运维', desc: '查看会话存储、缓存与 Spring Cloud 组件运行状态', render: renderOps}
};

function buildMenu() {
    let html = '';
    MENU.forEach(g => {
        // 按角色权限过滤：整组都无权时连分组标题也不显示
        const items = g.items.filter(i => canSee(i.key));
        if (!items.length) return;
        html += `<div class="menu-group">${g.group}</div>`;
        items.forEach(i => {
            html += `<div class="menu-item" data-key="${i.key}"><span class="menu-icon">${i.icon}</span>${i.label}</div>`;
        });
    });
    $('#menu').innerHTML = html;
    $$('#menu .menu-item').forEach(el => el.onclick = () => navigate(el.dataset.key));
}

function navigate(key) {
    const p = PAGES[key];
    if (!p) return;
    if (!canSee(key)) {
        toast('当前角色无权访问该模块', 'err');
        return;
    }
    $$('#menu .menu-item').forEach(el => el.classList.toggle('active', el.dataset.key === key));
    $('#pageTitle').textContent = p.title;
    $('#pageDesc').textContent = p.desc;
    $('#content').innerHTML = '<div class="loading">正在加载数据…</div>';
    Promise.resolve(p.render())
        .then(() => mountExportButtonForPage(key))
        .catch(e => {
            $('#content').innerHTML = `<div class="card"><div class="empty">加载失败：${esc(e.message)}</div></div>`;
        });
}

/* ==================== 通用渲染 ==================== */
function cell(r, c) {
    const v = r[c.k];
    if (c.type === 'money') return `<td class="num">${money(v)}</td>`;
    if (c.type === 'shortMoney') return `<td class="num">${shortMoney(v)}</td>`;
    if (c.type === 'time') return `<td>${esc(fmtTime(v))}</td>`;
    if (c.type === 'status') return `<td><span class="badge ${Number(v) === 1 ? 'green' : 'gray'}">${Number(v) === 1 ? '启用' : '停用'}</span></td>`;
    if (c.type === 'orderStatus') return `<td>${badge(v, STATUS_MAP)}</td>`;
    if (c.type === 'flowType') return `<td>${badge(v, FLOW_MAP)}</td>`;
    if (c.type === 'html') return `<td>${c.render(r)}</td>`;
    return `<td>${esc(v ?? '-')}</td>`;
}

function tableHtml(cols, rows, actions) {
    if (!rows || !rows.length) return '<div class="empty">暂无数据</div>';
    const head = cols.map(c => `<th>${c.t}</th>`).join('') + (actions ? '<th>操作</th>' : '');
    const body = rows.map(r =>
        `<tr>${cols.map(c => cell(r, c)).join('')}${actions ? `<td style="white-space:nowrap">${actions(r)}</td>` : ''}</tr>`
    ).join('');
    return `<div class="tbl-wrap"><table class="tbl"><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`;
}

function openModal({title, body, width = 620, footer}) {
    $('#modalRoot').innerHTML = `
        <div class="modal-mask">
          <div class="modal" style="width:${width}px">
            <div class="modal-head"><h3>${title}</h3><button class="modal-x" data-close>×</button></div>
            <div class="modal-body">${body}</div>
            <div class="modal-foot">${footer || '<button class="btn" data-close>关闭</button>'}</div>
          </div>
        </div>`;
    $$('#modalRoot [data-close]').forEach(b => b.onclick = closeModal);
}

function closeModal() {
    $('#modalRoot').innerHTML = '';
}

const modalFooter = (submitText = '保存') =>
    `<button class="btn" data-close>取消</button><button class="btn primary" id="modalSubmit">${submitText}</button>`;

/* ==================== 经营看板 ==================== */
function barChart(trend) {
    const W = 700, H = 230, padL = 54, padR = 16, base = H - 28, top = 14;
    const max = Math.max(1, ...trend.flatMap(t => [Number(t.sales), Number(t.purchase)]));
    const groupW = (W - padL - padR) / trend.length;
    let grid = '';
    for (let i = 0; i <= 4; i++) {
        const y = top + (base - top) * i / 4;
        grid += `<line x1="${padL}" y1="${y}" x2="${W - padR}" y2="${y}" stroke="#eef2f7"/>`;
        grid += `<text x="${padL - 8}" y="${y + 4}" text-anchor="end" font-size="10" fill="#94a3b8">${shortNum(max * (4 - i) / 4)}</text>`;
    }
    let bars = '';
    trend.forEach((t, i) => {
        const x = padL + i * groupW;
        const h1 = Number(t.sales) / max * (base - top);
        const h2 = Number(t.purchase) / max * (base - top);
        const bw = Math.min(20, groupW * 0.24);
        bars += `<rect x="${x + groupW * 0.20}" y="${base - h1}" width="${bw}" height="${Math.max(h1, 1)}" rx="3" fill="#2563eb"><title>${t.date} 销售 ${money(t.sales)}</title></rect>`;
        bars += `<rect x="${x + groupW * 0.52}" y="${base - h2}" width="${bw}" height="${Math.max(h2, 1)}" rx="3" fill="#93c5fd"><title>${t.date} 采购 ${money(t.purchase)}</title></rect>`;
        bars += `<text x="${x + groupW * 0.5}" y="${base + 18}" text-anchor="middle" font-size="11" fill="#64748b">${t.date}</text>`;
    });
    grid += `<line x1="${padL}" y1="${base}" x2="${W - padR}" y2="${base}" stroke="#cbd5e1"/>`;
    return `<svg class="chart-svg" viewBox="0 0 ${W} ${H}" preserveAspectRatio="xMidYMid meet">${grid}${bars}</svg>`;
}

async function renderDashboard() {
    const d = await api('/dashboard/summary');
    const m = d.metrics;
    const kpis = [
        {label: '本月销售额', value: shortMoney(m.monthSales), hint: '已发货销售订单', icon: '↗', color: '#eff5ff', fg: '#2563eb'},
        {label: '本月采购额', value: shortMoney(m.monthPurchase), hint: '已入库采购订单', icon: '↙', color: '#fff2e8', fg: '#ea580c'},
        {label: '库存总成本', value: shortMoney(m.stockValue), hint: '按商品成本价计算', icon: '▤', color: '#eaf8ee', fg: '#16a34a'},
        {label: '本月销售单数', value: m.monthSalesOrders, hint: '全部状态销售订单', icon: '≡', color: '#f3f0ff', fg: '#7c3aed'},
        {label: '在售商品', value: m.products, hint: '商品档案总数', icon: '◈', color: '#eff5ff', fg: '#2563eb'},
        {label: '往来单位', value: Number(m.customers) + Number(m.suppliers), hint: `客户 ${m.customers} · 供应商 ${m.suppliers}`, icon: '◎', color: '#eaf8ee', fg: '#16a34a'},
        {label: '低库存预警', value: m.lowStock, hint: '低于或等于安全库存', icon: '!', color: '#fef0f0', fg: '#dc2626'},
        {label: '待处理单据', value: Number(m.pendingPurchase) + Number(m.pendingSales), hint: `待入库 ${m.pendingPurchase} · 待发货 ${m.pendingSales}`, icon: '◷', color: '#fff2e8', fg: '#ea580c'}
    ];
    const kpiHtml = kpis.map(k => `
        <div class="kpi">
          <div class="kpi-label">${k.label}<span class="kpi-icon" style="background:${k.color};color:${k.fg}">${k.icon}</span></div>
          <div class="kpi-value">${k.value}</div>
          <div class="kpi-hint">${esc(k.hint)}</div>
        </div>`).join('');

    const rankHtml = (d.topProducts || []).length
        ? d.topProducts.map((p, i) => `
            <div class="rank-row">
              <div class="rank-no ${i < 3 ? 'top' : ''}">${i + 1}</div>
              <div class="rank-name">${esc(p.name)}<span style="color:#94a3b8;font-size:11.5px"> · ${p.qty} 件</span></div>
              <div class="rank-value">${money(p.amount)}</div>
            </div>`).join('')
        : '<div class="empty">暂无销售数据</div>';

    const lowHtml = (d.lowStockList || []).length
        ? d.lowStockList.map(p => `
            <div class="rank-row">
              <div class="rank-no" style="background:#fef0f0;color:#dc2626">!</div>
              <div class="rank-name">${esc(p.name)}<span style="color:#94a3b8;font-size:11.5px"> · ${esc(p.warehouse_name)}</span></div>
              <div class="rank-value" style="color:#dc2626">${p.quantity} / ${p.safety_stock} ${esc(p.unit)}</div>
            </div>`).join('')
        : '<div class="empty">库存充足，暂无预警</div>';

    const recentCols = [
        {k: 'type', t: '类型'}, {k: 'order_no', t: '单据编号'},
        {k: 'partner_name', t: '往来单位'},
        {k: 'total_amount', t: '金额', type: 'money'},
        {k: 'status', t: '状态', type: 'orderStatus'},
        {k: 'created_at', t: '创建时间', type: 'time'}
    ];

    $('#content').innerHTML = `
        <div class="kpi-grid">${kpiHtml}</div>
        <div class="two-col">
          <div class="card">
            <div class="card-head">
              <div class="card-title">近 7 日进销趋势 <small>单位：元</small></div>
              <div class="chart-legend">
                <span><i class="legend-dot" style="background:#2563eb"></i>销售额</span>
                <span><i class="legend-dot" style="background:#93c5fd"></i>采购额</span>
              </div>
            </div>
            <div class="card-body">${barChart(d.trend || [])}</div>
          </div>
          <div class="card">
            <div class="card-head"><div class="card-title">热销商品 TOP 5</div></div>
            <div class="card-body">${rankHtml}</div>
          </div>
        </div>
        <div class="two-col">
          <div class="card">
            <div class="card-head"><div class="card-title">最近单据</div></div>
            ${tableHtml(recentCols, d.recentOrders || [])}
          </div>
          <div class="card">
            <div class="card-head"><div class="card-title">库存预警 <small>低于安全库存</small></div></div>
            <div class="card-body">${lowHtml}</div>
          </div>
        </div>`;
}

/* ==================== 基础资料 ==================== */
const STATUS_FIELD = {
    k: 'status', t: '状态', type: 'select', def: 1,
    options: [[1, '启用'], [0, '停用']]
};
const MASTER_CONF = {
    product: {
        label: '商品', api: 'product',
        columns: [
            {k: 'code', t: '商品编码'}, {k: 'name', t: '商品名称'}, {k: 'category', t: '分类'},
            {k: 'spec', t: '规格'}, {k: 'unit', t: '单位'},
            {k: 'cost_price', t: '成本价', type: 'money'}, {k: 'sale_price', t: '销售价', type: 'money'},
            {k: 'safety_stock', t: '安全库存'}, {k: 'status', t: '状态', type: 'status'}
        ],
        fields: [
            {k: 'code', t: '商品编码', required: true}, {k: 'name', t: '商品名称', required: true},
            {k: 'category', t: '商品分类'}, {k: 'spec', t: '规格型号'},
            {k: 'unit', t: '计量单位', def: '个'},
            {k: 'cost_price', t: '成本价', type: 'number', def: 0},
            {k: 'sale_price', t: '销售价', type: 'number', def: 0},
            {k: 'safety_stock', t: '安全库存', type: 'number', def: 0}, STATUS_FIELD
        ]
    },
    supplier: {
        label: '供应商', api: 'supplier',
        columns: [
            {k: 'code', t: '供应商编码'}, {k: 'name', t: '供应商名称'}, {k: 'contact', t: '联系人'},
            {k: 'phone', t: '联系电话'}, {k: 'address', t: '地址'}, {k: 'status', t: '状态', type: 'status'}
        ],
        fields: [
            {k: 'code', t: '供应商编码', required: true}, {k: 'name', t: '供应商名称', required: true},
            {k: 'contact', t: '联系人'}, {k: 'phone', t: '联系电话'},
            {k: 'address', t: '地址', full: true}, STATUS_FIELD
        ]
    },
    customer: {
        label: '客户', api: 'customer',
        columns: [
            {k: 'code', t: '客户编码'}, {k: 'name', t: '客户名称'}, {k: 'contact', t: '联系人'},
            {k: 'phone', t: '联系电话'}, {k: 'address', t: '地址'}, {k: 'status', t: '状态', type: 'status'}
        ],
        fields: [
            {k: 'code', t: '客户编码', required: true}, {k: 'name', t: '客户名称', required: true},
            {k: 'contact', t: '联系人'}, {k: 'phone', t: '联系电话'},
            {k: 'address', t: '地址', full: true}, STATUS_FIELD
        ]
    },
    warehouse: {
        label: '仓库', api: 'warehouse',
        columns: [
            {k: 'code', t: '仓库编码'}, {k: 'name', t: '仓库名称'},
            {k: 'address', t: '地址'}, {k: 'status', t: '状态', type: 'status'}
        ],
        fields: [
            {k: 'code', t: '仓库编码', required: true}, {k: 'name', t: '仓库名称', required: true},
            {k: 'address', t: '仓库地址', full: true}, STATUS_FIELD
        ]
    },
    // 借鉴金蝶「组织与人资基础资料」：部门与职员共用同一套通用 CRUD 配置
    department: {
        label: '部门', api: 'department',
        columns: [
            {k: 'code', t: '部门编码'}, {k: 'name', t: '部门名称'},
            {k: 'manager', t: '负责人'}, {k: 'phone', t: '联系电话'},
            {k: 'status', t: '状态', type: 'status'}
        ],
        fields: [
            {k: 'code', t: '部门编码', required: true}, {k: 'name', t: '部门名称', required: true},
            {k: 'manager', t: '部门负责人'}, {k: 'phone', t: '联系电话'}, STATUS_FIELD
        ]
    },
    employee: {
        label: '职员', api: 'employee',
        columns: [
            {k: 'code', t: '职员编码'}, {k: 'name', t: '姓名'},
            {k: 'department', t: '所属部门'}, {k: 'position', t: '岗位'},
            {k: 'phone', t: '联系电话'}, {k: 'status', t: '状态', type: 'status'}
        ],
        fields: [
            {k: 'code', t: '职员编码', required: true}, {k: 'name', t: '姓名', required: true},
            {k: 'department', t: '所属部门'}, {k: 'position', t: '岗位'},
            {k: 'phone', t: '联系电话'}, STATUS_FIELD
        ]
    }
};

function fieldHtml(f, row) {
    const val = row ? (row[f.k] ?? '') : (f.def ?? '');
    if (f.type === 'select') {
        return `<div class="form-item"><label>${f.t}</label><select class="select" data-f="${f.k}">
            ${f.options.map(([v, t]) => `<option value="${v}" ${String(val) === String(v) ? 'selected' : ''}>${t}</option>`).join('')}
        </select></div>`;
    }
    return `<div class="form-item ${f.full ? 'full' : ''}">
        <label>${f.t}${f.required ? '<span class="req">*</span>' : ''}</label>
        <input class="input" data-f="${f.k}" type="${f.type === 'number' ? 'number' : 'text'}"
               ${f.type === 'number' ? 'step="0.01" min="0"' : ''} value="${esc(val)}">
    </div>`;
}

async function renderMaster(type) {
    const c = MASTER_CONF[type];
    $('#content').innerHTML = `
        <div class="card">
          <div class="toolbar">
            <input id="kw" class="input w220" placeholder="搜索编码 / 名称">
            <button class="btn" id="btnSearch">查询</button>
            <div class="spacer"></div>
            <button class="btn primary" id="btnAdd">+ 新增${c.label}</button>
          </div>
          <div id="tableBox"></div>
        </div>`;
    const load = async () => {
        $('#tableBox').innerHTML = '<div class="loading">加载中…</div>';
        try {
            const rows = await api(`/master/${type}`, {params: {keyword: $('#kw').value.trim()}});
            $('#tableBox').innerHTML = tableHtml(c.columns, rows, r => `
                <button class="btn link" data-act="edit" data-id="${r.id}">编辑</button>
                <button class="btn link danger" data-act="del" data-id="${r.id}">删除</button>`);
            $$('#tableBox [data-act]').forEach(b => b.onclick = async () => {
                const id = b.dataset.id;
                if (b.dataset.act === 'edit') {
                    try {
                        const row = await api(`/master/${type}/${id}`);
                        openMasterForm(type, row, load);
                    } catch (e) { toast(e.message, 'err'); }
                } else {
                    if (!confirm('确定删除这条记录吗？')) return;
                    try {
                        await api(`/master/${type}/${id}`, {method: 'DELETE'});
                        toast('删除成功');
                        refreshOptions();
                        load();
                    } catch (e) { toast(e.message, 'err'); }
                }
            });
        } catch (e) {
            $('#tableBox').innerHTML = `<div class="empty">${esc(e.message)}</div>`;
        }
    };
    $('#btnSearch').onclick = load;
    $('#kw').onkeydown = e => { if (e.key === 'Enter') load(); };
    $('#btnAdd').onclick = () => openMasterForm(type, null, load);
    await load();
}

function openMasterForm(type, row, reload) {
    const c = MASTER_CONF[type];
    openModal({
        title: (row ? '编辑' : '新增') + c.label,
        width: 660,
        body: `<div class="form-grid">${c.fields.map(f => fieldHtml(f, row)).join('')}</div>`,
        footer: modalFooter()
    });
    $('#modalSubmit').onclick = async () => {
        const body = {};
        c.fields.forEach(f => {
            const el = $(`[data-f="${f.k}"]`, $('#modalRoot'));
            let v = String(el.value).trim();
            if (f.type === 'number' || f.type === 'select') v = v === '' ? 0 : Number(v);
            body[f.k] = v;
        });
        if (!body.code || !body.name) return toast('编码和名称不能为空', 'err');
        const btn = $('#modalSubmit');
        btn.disabled = true;
        try {
            await api(row ? `/master/${type}/${row.id}` : `/master/${type}`,
                {method: row ? 'PUT' : 'POST', body});
            toast(row ? '修改成功' : '新增成功');
            closeModal();
            refreshOptions();
            reload();
        } catch (e) {
            toast(e.message, 'err');
            btn.disabled = false;
        }
    };
}

/* ---- 基础资料下拉：浏览器侧缓存 ----
   服务端缓存解决「数据库查得慢」，浏览器缓存解决「网络来回慢」。
   缓存放在 sessionStorage（关掉标签页即失效），并设 60 秒兜底 TTL；
   任何写请求成功后由 api() 调用 invalidateOptions() 主动清除。 */
const OPTIONS_CACHE_KEY = 'erp_options_cache';
const OPTIONS_CACHE_TTL = 60_000;

function readOptionsCache() {
    try {
        const raw = sessionStorage.getItem(OPTIONS_CACHE_KEY);
        if (!raw) return;
        const c = JSON.parse(raw);
        if (c && c.data && Date.now() - c.at < OPTIONS_CACHE_TTL) OPTIONS = c.data;
    } catch (e) { /* 缓存损坏就当没有，不影响功能 */ }
}

function invalidateOptions() {
    try { sessionStorage.removeItem(OPTIONS_CACHE_KEY); } catch (e) { /* 忽略 */ }
}

async function refreshOptions() {
    try {
        OPTIONS = await api('/master/options');
        try {
            sessionStorage.setItem(OPTIONS_CACHE_KEY, JSON.stringify({at: Date.now(), data: OPTIONS}));
        } catch (e) { /* 隐私模式下 sessionStorage 不可用，忽略 */ }
    } catch (e) { /* 保留已有 OPTIONS，避免下拉框突然变空 */ }
}

/* ==================== 系统运维：Redis / Spring Cloud 运行时状态 ==================== */
function kvTable(obj) {
    return `<table class="tbl"><tbody>${Object.entries(obj || {}).map(([k, v]) => {
        const text = (v && typeof v === 'object') ? JSON.stringify(v) : String(v ?? '-');
        return `<tr><td>${esc(k)}</td><td style="text-align:left">${esc(text)}</td></tr>`;
    }).join('')}</tbody></table>`;
}

async function renderOps() {
    const d = await api('/ops/runtime');
    const cloudCard = d.cloudEnabled
        ? `${kvTable(d.cloud)}
           <button class="btn primary" id="btnProbe" style="margin-top:12px">测试 Feign 调用与熔断</button>
           <pre id="probeResult" style="white-space:pre-wrap;word-break:break-all;background:#f8fafc;padding:10px;border-radius:6px;font-size:12px;max-height:260px;overflow:auto;margin-top:10px">点击上方按钮发起一次 OpenFeign 调用</pre>`
        : `<div class="empty">Spring Cloud 未启用（当前是零配置单体模式）<br>用 start-cloud.bat 启动后，这里会显示 Feign 客户端与熔断器状态</div>`;

    $('#content').innerHTML = `
        <div class="two-col">
          <div class="card">
            <div class="card-head"><div class="card-title">运行环境</div></div>
            <div class="card-body">${kvTable({
                '应用': d.application,
                '启用 Profile': d.profiles,
                'Java 版本': d.javaVersion
            })}</div>
          </div>
          <div class="card">
            <div class="card-head"><div class="card-title">会话存储 <small>type=redis 表示登录态已共享</small></div></div>
            <div class="card-body">${kvTable(d.session)}</div>
          </div>
        </div>
        <div class="two-col" style="margin-top:16px">
          <div class="card">
            <div class="card-head"><div class="card-title">业务缓存</div></div>
            <div class="card-body">${kvTable(d.cache)}</div>
          </div>
          <div class="card">
            <div class="card-head"><div class="card-title">Spring Cloud 组件</div></div>
            <div class="card-body">${cloudCard}</div>
          </div>
        </div>`;

    const btn = $('#btnProbe');
    if (btn) {
        btn.onclick = async () => {
            btn.disabled = true;
            $('#probeResult').textContent = '调用中…';
            try {
                const r = await api('/ops/cloud-probe');
                $('#probeResult').textContent = JSON.stringify(r, null, 2);
            } catch (e) {
                $('#probeResult').textContent = '调用失败：' + e.message;
            } finally {
                btn.disabled = false;
            }
        };
    }
}

/* ==================== AI 经营分析 ==================== */
/*
 * 极简 Markdown 渲染器：只支持 ## 标题 / - 列表 / **加粗** / 普通段落。
 * 先把全部文本 HTML 转义再做替换，LLM 返回任何内容都不会产生 XSS。
 */
function mdLite(src) {
    const inline = (s) => esc(s).replace(/\*\*(.+?)\*\*/g, '<b>$1</b>');
    let html = '', inList = false;
    String(src || '').split('\n').forEach(line => {
        const t = line.trim();
        const closeList = () => { if (inList) { html += '</ul>'; inList = false; } };
        if (!t) { closeList(); return; }
        if (t.startsWith('### ')) { closeList(); html += `<h4>${inline(t.slice(4))}</h4>`; }
        else if (t.startsWith('## ')) { closeList(); html += `<h3>${inline(t.slice(3))}</h3>`; }
        else if (t.startsWith('# ')) { closeList(); html += `<h3>${inline(t.slice(2))}</h3>`; }
        else if (t.startsWith('- ') || t.startsWith('* ')) {
            if (!inList) { html += '<ul>'; inList = true; }
            html += `<li>${inline(t.slice(2))}</li>`;
        } else { closeList(); html += `<p>${inline(t)}</p>`; }
    });
    if (inList) html += '</ul>';
    return html;
}

const AI_TYPES = [
    {key: 'overview', label: '经营总览', desc: '全局指标 + 趋势 + 往来款'},
    {key: 'sales', label: '销售分析', desc: '销售额 / 热销品 / 回款'},
    {key: 'inventory', label: '库存分析', desc: '库存价值 / 缺货预警 / 周转'},
    {key: 'finance', label: '财务分析', desc: '应收应付 / 逾期 / 凭证'}
];

async function renderAi() {
    const st = await api('/ai/status');
    const isLlm = st.mode === 'llm';
    $('#content').innerHTML = `
        <div class="card">
          <div class="card-head"><div class="card-title">分析引擎</div></div>
          <div class="card-body">
            <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap">
              <span class="badge ${isLlm ? 'green' : 'blue'}">${isLlm ? '已接入大模型' : '本地规则引擎'}</span>
              <span style="color:#64748b;font-size:13px">
                ${isLlm ? `模型 ${esc(st.model)} · ${esc(st.baseUrl)}`
                        : '未配置 LLM 密钥（环境变量 ERP_AI_API_KEY），当前由内置规则引擎生成分析，配好密钥后自动切换大模型'}
              </span>
            </div>
            <div style="margin-top:10px;font-size:12px;color:#94a3b8">
              数据全部来自系统实时汇总（与经营看板同源），AI 只负责解读、不接触数据库；
              结果带缓存，任何业务写操作后自动重新生成。
            </div>
          </div>
        </div>
        <div class="card" style="margin-top:16px">
          <div class="card-head"><div class="card-title">选择分析主题</div></div>
          <div class="card-body">
            <div style="display:flex;gap:10px;flex-wrap:wrap" id="aiTypes">
              ${AI_TYPES.map(t => `<button class="btn" data-type="${t.key}" style="padding:10px 16px">
                  <b>${t.label}</b><br><small style="color:#94a3b8">${t.desc}</small></button>`).join('')}
            </div>
            <div id="aiResult" style="margin-top:16px">
              <div class="empty">点击上方任一主题，生成一份基于实时数据的分析报告</div>
            </div>
          </div>
        </div>`;

    $$('#aiTypes button').forEach(btn => {
        btn.onclick = async () => {
            const type = btn.dataset.type;
            $$('#aiTypes button').forEach(b => b.disabled = true);
            $('#aiResult').innerHTML = '<div class="loading">正在分析实时业务数据，大模型可能需要十几秒…</div>';
            try {
                const r = await api('/ai/analyze', {params: {type}});
                $('#aiResult').innerHTML = `
                    <div style="display:flex;align-items:center;gap:8px;margin-bottom:8px;flex-wrap:wrap">
                      <b style="font-size:15px">${esc(r.typeLabel)}报告</b>
                      <span class="badge ${r.source === 'llm' ? 'green' : 'blue'}">${esc(r.sourceLabel)}</span>
                      <span style="font-size:12px;color:#94a3b8">生成于 ${esc(r.generatedAt)}</span>
                    </div>
                    <div class="md-body">${mdLite(r.content)}</div>`;
            } catch (e) {
                $('#aiResult').innerHTML = `<div class="empty">分析失败：${esc(e.message)}</div>`;
            } finally {
                $$('#aiTypes button').forEach(b => b.disabled = false);
            }
        };
    });
}

/* ==================== 采购 / 销售订单 ==================== */
const ORDER_CONF = {
    purchase: {
        label: '采购订单', api: '/purchase/orders', partnerLabel: '供应商',
        partnerField: 'supplier_id', partnerCol: 'supplier_name',
        finishLabel: '收货入库', finishPath: 'receive'
    },
    sales: {
        label: '销售订单', api: '/sales/orders', partnerLabel: '客户',
        partnerField: 'customer_id', partnerCol: 'customer_name',
        finishLabel: '发货出库', finishPath: 'ship'
    }
};

async function renderOrders(kind) {
    const c = ORDER_CONF[kind];
    $('#content').innerHTML = `
        <div class="card">
          <div class="toolbar">
            <select id="fStatus" class="select">
              <option value="">全部状态</option>
              ${Object.entries(STATUS_MAP).map(([v, s]) => `<option value="${v}">${s.t}</option>`).join('')}
            </select>
            <input id="kw" class="input w220" placeholder="搜索单号 / 往来单位">
            <button class="btn" id="btnSearch">查询</button>
            <div class="spacer"></div>
            <button class="btn primary" id="btnAdd">+ 新建${c.label}</button>
          </div>
          <div id="tableBox"></div>
        </div>`;
    const cols = [
        {k: 'order_no', t: '单据编号'}, {k: c.partnerCol, t: c.partnerLabel},
        {k: 'warehouse_name', t: '仓库'}, {k: 'item_count', t: '明细数'},
        {k: 'total_amount', t: '订单金额', type: 'money'},
        {k: 'status', t: '状态', type: 'orderStatus'},
        {k: 'created_at', t: '创建时间', type: 'time'}
    ];
    const load = async () => {
        $('#tableBox').innerHTML = '<div class="loading">加载中…</div>';
        try {
            const rows = await api(c.api, {
                params: {status: $('#fStatus').value, keyword: $('#kw').value.trim()}
            });
            $('#tableBox').innerHTML = tableHtml(cols, rows, r => {
                let s = `<button class="btn link" data-act="detail" data-id="${r.id}">详情</button>`;
                if (r.status === 'DRAFT') {
                    s += `<button class="btn link" data-act="confirm" data-id="${r.id}">审核</button>
                          <button class="btn link danger" data-act="cancel" data-id="${r.id}">取消</button>`;
                } else if (r.status === 'CONFIRMED') {
                    s += `<button class="btn link" data-act="finish" data-id="${r.id}">${c.finishLabel}</button>
                          <button class="btn link danger" data-act="cancel" data-id="${r.id}">取消</button>`;
                }
                return s;
            });
            $$('#tableBox [data-act]').forEach(b => b.onclick = async () => {
                const {act, id} = b.dataset;
                if (act === 'detail') return openOrderDetail(kind, id, load);
                if ((act === 'finish' || act === 'cancel') &&
                    !confirm(act === 'finish' ? `确定执行「${c.finishLabel}」吗？该操作会即时变更库存。` : '确定取消该订单吗？')) return;
                const path = act === 'finish' ? c.finishPath : act;
                try {
                    await api(`${c.api}/${id}/${path}`, {method: 'POST'});
                    toast('操作成功');
                    load();
                } catch (e) { toast(e.message, 'err'); }
            });
        } catch (e) {
            $('#tableBox').innerHTML = `<div class="empty">${esc(e.message)}</div>`;
        }
    };
    $('#btnSearch').onclick = load;
    $('#fStatus').onchange = load;
    $('#kw').onkeydown = e => { if (e.key === 'Enter') load(); };
    $('#btnAdd').onclick = () => openOrderForm(kind, load);
    await load();
}

function itemRow(kind) {
    const priceKey = kind === 'purchase' ? 'cost_price' : 'sale_price';
    const opts = (OPTIONS.products || []).map(p =>
        `<option value="${p.id}" data-price="${p[priceKey] ?? 0}">${esc(p.code)} ${esc(p.name)}</option>`).join('');
    const firstPrice = (OPTIONS.products || [])[0]?.[priceKey] ?? 0;
    return `<tr>
        <td><select class="select item-product" style="width:100%">${opts}</select></td>
        <td><input class="input item-qty" type="number" min="1" value="1" style="width:88px"></td>
        <td><input class="input item-price" type="number" min="0" step="0.01" value="${firstPrice}" style="width:108px"></td>
        <td class="item-amount money">¥0.00</td>
        <td><button class="btn link danger item-del">移除</button></td>
    </tr>`;
}

function recalcOrderTotal() {
    let total = 0;
    $$('#orderItems tr').forEach(tr => {
        const q = Number($('.item-qty', tr).value || 0);
        const p = Number($('.item-price', tr).value || 0);
        $('.item-amount', tr).textContent = money(q * p);
        total += q * p;
    });
    const el = $('#orderTotal');
    if (el) el.textContent = money(total);
}

function bindOrderItems() {
    const tbody = $('#orderItems');
    tbody.onchange = e => {
        if (e.target.classList.contains('item-product')) {
            const price = e.target.selectedOptions[0]?.dataset.price || 0;
            $('.item-price', e.target.closest('tr')).value = price;
        }
        recalcOrderTotal();
    };
    tbody.oninput = recalcOrderTotal;
    tbody.onclick = e => {
        if (e.target.classList.contains('item-del')) {
            e.target.closest('tr').remove();
            recalcOrderTotal();
        }
    };
    recalcOrderTotal();
}

async function openOrderForm(kind, reload) {
    await refreshOptions();
    const c = ORDER_CONF[kind];
    const partners = kind === 'purchase' ? OPTIONS.suppliers : OPTIONS.customers;
    const partnerOpts = (partners || []).map(p =>
        `<option value="${p.id}">${esc(p.code)} ${esc(p.name)}</option>`).join('');
    const whOpts = (OPTIONS.warehouses || []).map(w =>
        `<option value="${w.id}">${esc(w.name)}</option>`).join('');
    openModal({
        title: '新建' + c.label, width: 860,
        body: `
          <div class="form-grid">
            <div class="form-item"><label>${c.partnerLabel}<span class="req">*</span></label>
              <select class="select" id="oPartner">${partnerOpts}</select></div>
            <div class="form-item"><label>${kind === 'purchase' ? '入库仓库' : '出库仓库'}<span class="req">*</span></label>
              <select class="select" id="oWarehouse">${whOpts}</select></div>
            <div class="form-item full"><label>备注</label>
              <input class="input" id="oRemark" placeholder="选填，例如：双十一备货"></div>
          </div>
          <div class="items-toolbar"><span>订单明细</span><button class="btn sm" id="btnAddItem">+ 添加商品</button></div>
          <div class="tbl-wrap"><table class="tbl">
            <thead><tr><th style="width:42%">商品</th><th>数量</th><th>单价</th><th>小计</th><th></th></tr></thead>
            <tbody id="orderItems"></tbody>
          </table></div>
          <div class="order-total">订单合计 <b id="orderTotal">¥0.00</b></div>`,
        footer: modalFooter('保存草稿')
    });
    const addItem = () => {
        $('#orderItems').insertAdjacentHTML('beforeend', itemRow(kind));
        bindOrderItems();
    };
    $('#btnAddItem').onclick = addItem;
    addItem();
    $('#modalSubmit').onclick = async () => {
        const items = [];
        $$('#orderItems tr').forEach(tr => {
            const product_id = Number($('.item-product', tr).value);
            const quantity = Number($('.item-qty', tr).value);
            const unit_price = Number($('.item-price', tr).value);
            if (product_id && quantity > 0) items.push({product_id, quantity, unit_price});
        });
        if (!items.length) return toast('请至少填写一条有效明细', 'err');
        const body = {items, remark: $('#oRemark').value.trim(), warehouse_id: Number($('#oWarehouse').value)};
        body[c.partnerField] = Number($('#oPartner').value);
        const btn = $('#modalSubmit');
        btn.disabled = true;
        try {
            await api(c.api, {method: 'POST', body});
            toast('订单已创建（草稿）');
            closeModal();
            reload();
        } catch (e) {
            toast(e.message, 'err');
            btn.disabled = false;
        }
    };
}

async function openOrderDetail(kind, id, reload) {
    const c = ORDER_CONF[kind];
    await refreshOptions();
    const d = await api(`${c.api}/${id}`);
    const partners = kind === 'purchase' ? OPTIONS.suppliers : OPTIONS.customers;
    const partnerName = (partners || []).find(p => String(p.id) === String(d[c.partnerField]))?.name || '-';
    const whName = (OPTIONS.warehouses || []).find(w => String(w.id) === String(d.warehouse_id))?.name || '-';
    const qtyField = kind === 'purchase' ? 'received_qty' : 'shipped_qty';
    const itemCols = [
        {k: 'product_code', t: '商品编码'}, {k: 'product_name', t: '商品名称'},
        {k: 'unit', t: '单位'}, {k: 'quantity', t: '数量'},
        {k: 'unit_price', t: '单价', type: 'money'}, {k: 'amount', t: '小计', type: 'money'},
        {k: qtyField, t: '已执行数量'}
    ];
    let actionBtns = '';
    if (d.status === 'DRAFT') actionBtns = `<button class="btn primary" data-dact="confirm">审核订单</button><button class="btn danger" data-dact="cancel">取消订单</button>`;
    if (d.status === 'CONFIRMED') actionBtns = `<button class="btn primary" data-dact="finish">${c.finishLabel}</button><button class="btn danger" data-dact="cancel">取消订单</button>`;
    openModal({
        title: `${c.label}详情 · ${esc(d.order_no)}`, width: 860,
        body: `
          <div class="detail-info">
            <div><span>${c.partnerLabel}</span><b>${esc(partnerName)}</b></div>
            <div><span>仓库</span><b>${esc(whName)}</b></div>
            <div><span>状态</span><b>${badge(d.status, STATUS_MAP)}</b></div>
            <div><span>订单金额</span><b style="color:#2563eb">${money(d.total_amount)}</b></div>
            <div><span>创建人 / 时间</span><b>${esc(d.created_by || '-')} · ${esc(fmtTime(d.created_at))}</b></div>
            <div><span>备注</span><b>${esc(d.remark || '-')}</b></div>
          </div>
          ${tableHtml(itemCols, d.items || [])}`,
        footer: `<button class="btn" data-close>关闭</button>${actionBtns}`
    });
    $$('#modalRoot [data-dact]').forEach(b => b.onclick = async () => {
        const act = b.dataset.dact;
        if ((act === 'finish' || act === 'cancel') &&
            !confirm(act === 'finish' ? `确定执行「${c.finishLabel}」吗？` : '确定取消该订单吗？')) return;
        const path = act === 'finish' ? c.finishPath : act;
        try {
            await api(`${c.api}/${id}/${path}`, {method: 'POST'});
            toast('操作成功');
            closeModal();
            reload();
        } catch (e) { toast(e.message, 'err'); }
    });
}

/* ==================== 库存 / 流水 / 日志 ==================== */
async function renderStock() {
    await refreshOptions();
    $('#content').innerHTML = `
        <div class="card">
          <div class="toolbar">
            <select id="fWh" class="select"><option value="">全部仓库</option>
              ${(OPTIONS.warehouses || []).map(w => `<option value="${w.id}">${esc(w.name)}</option>`).join('')}
            </select>
            <input id="kw" class="input w220" placeholder="搜索商品编码 / 名称 / 分类">
            <label style="font-size:13px;color:#475569;display:flex;align-items:center;gap:5px;cursor:pointer">
              <input type="checkbox" id="fAlert"> 只看预警
            </label>
            <button class="btn" id="btnSearch">查询</button>
            <div class="spacer"></div>
            <button class="btn primary" id="btnAdjust">库存盘点</button>
          </div>
          <div id="tableBox"></div>
        </div>`;
    const cols = [
        {k: 'warehouse_name', t: '仓库'}, {k: 'code', t: '商品编码'},
        {k: 'product_name', t: '商品名称'}, {k: 'category', t: '分类'},
        {k: 'unit', t: '单位'},
        {k: 'quantity', t: '当前库存', type: 'html', render: r => `<b style="font-size:14px;color:${r.alert ? '#dc2626' : '#0f172a'}">${r.quantity}</b>`},
        {k: 'safety_stock', t: '安全库存'},
        {k: 'stock_value', t: '库存金额', type: 'money'},
        {k: 'alert', t: '状态', type: 'html', render: r => r.alert ? '<span class="badge red">库存预警</span>' : '<span class="badge green">正常</span>'}
    ];
    const load = async () => {
        $('#tableBox').innerHTML = '<div class="loading">加载中…</div>';
        try {
            const rows = await api('/inventory/stock', {
                params: {
                    warehouseId: $('#fWh').value, keyword: $('#kw').value.trim(),
                    alertOnly: $('#fAlert').checked
                }
            });
            $('#tableBox').innerHTML = tableHtml(cols, rows, r => `
                <button class="btn link" data-wh="${r.warehouse_id}" data-pid="${r.product_id}"
                        data-qty="${r.quantity}" data-name="${esc(r.product_name)}">盘点</button>`);
            $$('#tableBox [data-pid]').forEach(b => b.onclick = () =>
                openAdjust({warehouse_id: b.dataset.wh, product_id: b.dataset.pid, quantity: b.dataset.qty}, load));
        } catch (e) {
            $('#tableBox').innerHTML = `<div class="empty">${esc(e.message)}</div>`;
        }
    };
    $('#btnSearch').onclick = load;
    $('#fWh').onchange = load;
    $('#fAlert').onchange = load;
    $('#kw').onkeydown = e => { if (e.key === 'Enter') load(); };
    $('#btnAdjust').onclick = () => openAdjust(null, load);
    await load();
}

function openAdjust(row, reload) {
    const whOpts = (OPTIONS.warehouses || []).map(w =>
        `<option value="${w.id}" ${row && String(row.warehouse_id) === String(w.id) ? 'selected' : ''}>${esc(w.name)}</option>`).join('');
    const pOpts = (OPTIONS.products || []).map(p =>
        `<option value="${p.id}" ${row && String(row.product_id) === String(p.id) ? 'selected' : ''}>${esc(p.code)} ${esc(p.name)}</option>`).join('');
    openModal({
        title: '库存盘点', width: 560,
        body: `<div class="form-grid">
            <div class="form-item full"><label>仓库<span class="req">*</span></label><select class="select" id="aWh">${whOpts}</select></div>
            <div class="form-item full"><label>商品<span class="req">*</span></label><select class="select" id="aProduct">${pOpts}</select></div>
            <div class="form-item"><label>盘点后数量<span class="req">*</span></label>
              <input class="input" id="aQty" type="number" min="0" value="${row ? row.quantity : 0}"></div>
            <div class="form-item"><label>备注</label><input class="input" id="aRemark" placeholder="例如：月末盘点"></div>
          </div>
          <div style="margin-top:14px;color:#94a3b8;font-size:12px">盘点差额会自动写入库存流水，正数补入、负数报损。</div>`,
        footer: modalFooter('确认盘点')
    });
    $('#modalSubmit').onclick = async () => {
        const body = {
            warehouse_id: Number($('#aWh').value), product_id: Number($('#aProduct').value),
            quantity: Number($('#aQty').value), remark: $('#aRemark').value.trim()
        };
        if (!body.warehouse_id || !body.product_id) return toast('请选择仓库和商品', 'err');
        const btn = $('#modalSubmit');
        btn.disabled = true;
        try {
            await api('/inventory/adjust', {method: 'POST', body});
            toast('盘点完成，库存已更新');
            closeModal();
            reload();
        } catch (e) {
            toast(e.message, 'err');
            btn.disabled = false;
        }
    };
}

async function renderFlows() {
    $('#content').innerHTML = `
        <div class="card">
          <div class="toolbar">
            <select id="fType" class="select"><option value="">全部类型</option>
              ${Object.entries(FLOW_MAP).map(([v, s]) => `<option value="${v}">${s.t}</option>`).join('')}
            </select>
            <input id="kw" class="input w220" placeholder="搜索单号 / 商品">
            <button class="btn" id="btnSearch">查询</button>
            <div class="spacer"></div>
            <span style="font-size:12px;color:#94a3b8">最多显示最近 300 条</span>
          </div>
          <div id="tableBox"></div>
        </div>`;
    const cols = [
        {k: 'created_at', t: '时间', type: 'time'}, {k: 'biz_no', t: '业务单号'},
        {k: 'biz_type', t: '类型', type: 'flowType'},
        {k: 'warehouse_name', t: '仓库'}, {k: 'code', t: '商品编码'}, {k: 'product_name', t: '商品名称'},
        {k: 'change_qty', t: '变动数量', type: 'html', render: r =>
                `<b style="color:${Number(r.change_qty) >= 0 ? '#16a34a' : '#ea580c'}">${Number(r.change_qty) > 0 ? '+' : ''}${r.change_qty}</b>`},
        {k: 'before_qty', t: '变动前'}, {k: 'after_qty', t: '变动后'},
        {k: 'operator', t: '操作人'}, {k: 'remark', t: '备注'}
    ];
    const load = async () => {
        $('#tableBox').innerHTML = '<div class="loading">加载中…</div>';
        try {
            const rows = await api('/inventory/flows', {
                params: {bizType: $('#fType').value, keyword: $('#kw').value.trim()}
            });
            $('#tableBox').innerHTML = tableHtml(cols, rows);
        } catch (e) {
            $('#tableBox').innerHTML = `<div class="empty">${esc(e.message)}</div>`;
        }
    };
    $('#btnSearch').onclick = load;
    $('#fType').onchange = load;
    $('#kw').onkeydown = e => { if (e.key === 'Enter') load(); };
    await load();
}

async function renderLogs() {
    $('#content').innerHTML = `
        <div class="card">
          <div class="toolbar">
            <input id="kw" class="input w220" placeholder="搜索操作人 / 操作 / 详情">
            <button class="btn" id="btnSearch">查询</button>
            <div class="spacer"></div>
            <span style="font-size:12px;color:#94a3b8">最多显示最近 200 条</span>
          </div>
          <div id="tableBox"></div>
        </div>`;
    const cols = [
        {k: 'created_at', t: '时间', type: 'time'}, {k: 'username', t: '操作人'},
        {k: 'action', t: '操作类型'}, {k: 'detail', t: '操作详情'}, {k: 'ip', t: '来源'}
    ];
    const load = async () => {
        $('#tableBox').innerHTML = '<div class="loading">加载中…</div>';
        try {
            const rows = await api('/logs', {params: {keyword: $('#kw').value.trim()}});
            $('#tableBox').innerHTML = tableHtml(cols, rows);
        } catch (e) {
            $('#tableBox').innerHTML = `<div class="empty">${esc(e.message)}</div>`;
        }
    };
    $('#btnSearch').onclick = load;
    $('#kw').onkeydown = e => { if (e.key === 'Enter') load(); };
    await load();
}

/* ======================================================================
 * 以下为「借鉴金蝶 K/3」扩展模块的页面
 * ----------------------------------------------------------------------
 * 前端沿用两部分既有约定，新增页面不需要写任何后端模板代码：
 *   1. api(path, {params, body}) 统一走 /api + X-Token
 *   2. tableHtml(cols, rows, actions) 统一渲染表格，actions 返回操作按钮 HTML
 * 因此每个页面基本是「拼工具栏 → 定义列 → load() 拉数据 → 绑定操作」。
 * ====================================================================== */

/* ---------- 通用小工具（扩展模块共用） ---------- */

/** 本地日期 yyyy-MM-dd（避免 toISOString 的 UTC 时区导致差一天） */
function localDate(d = new Date()) {
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** 本地会计期间 yyyy-MM */
function localPeriod() {
    return localDate().slice(0, 7);
}

/** 由数据行生成 <option> 列表；label 为拼接函数，便于显示「编码 名称」 */
function optionHtml(rows, valueKey, labelFn, selected) {
    return (rows || []).map(r =>
        `<option value="${esc(r[valueKey])}" ${String(r[valueKey]) === String(selected ?? '') ? 'selected' : ''}>${esc(labelFn(r))}</option>`
    ).join('');
}

/** 简易页签：点击后切换激活态并执行回调，用于「余额表 / 明细账」等同页多视图 */
function bindTabs(root, onChange) {
    $$(root + ' [data-tab]').forEach(b => b.onclick = () => {
        $$(root + ' [data-tab]').forEach(x => {
            x.classList.toggle('active', x === b);
            x.classList.toggle('primary', x === b); // 激活页签用主色填充
        });
        onChange(b.dataset.tab);
    });
}

/** 科目下拉选项（凭证录入、明细账共用），数据来自 /api/finance/subjects */
async function subjectOptions(selected) {
    const rows = await api('/finance/subjects');
    return optionHtml(rows.filter(s => Number(s.is_leaf) === 1 && Number(s.status) === 1),
        'code', s => `${s.code} ${s.name}`, selected);
}

const SOURCE_MAP_HTML = (v) => `<span class="badge ${v === 'MANUAL' ? 'blue' : 'gray'}">${esc(VOUCHER_SOURCE_MAP[v] || v || '-')}</span>`;

/* ==================== 记账凭证（金蝶「凭证管理」） ==================== */
async function renderVouchers() {
    $('#content').innerHTML = `
        <div class="card">
          <div class="toolbar">
            <input id="fPeriod" class="input" type="month" style="width:150px" value="${localPeriod()}">
            <select id="fStatus" class="select">
              <option value="">全部状态</option>
              <option value="DRAFT">草稿</option><option value="POSTED">已过账</option>
            </select>
            <select id="fSource" class="select">
              <option value="">全部来源</option>
              ${Object.entries(VOUCHER_SOURCE_MAP).map(([k, v]) => `<option value="${k}">${v}</option>`).join('')}
            </select>
            <input id="kw" class="input w220" placeholder="搜索凭证号 / 摘要 / 业务单号">
            <button class="btn" id="btnSearch">查询</button>
            <div class="spacer"></div>
            <button class="btn primary" id="btnAdd">+ 录入凭证</button>
          </div>
          <div id="tableBox"></div>
        </div>`;
    const cols = [
        {k: 'voucher_date', t: '凭证日期'}, {k: 'voucher_no', t: '凭证号'},
        {k: 'voucher_word', t: '凭证字'}, {k: 'summary', t: '摘要'},
        {k: 'source_type', t: '来源', type: 'html', render: r => SOURCE_MAP_HTML(r.source_type)},
        {k: 'biz_no', t: '业务单号'},
        {k: 'total_debit', t: '借方合计', type: 'money'},
        {k: 'status', t: '状态', type: 'orderStatus'},
        {k: 'created_by', t: '制单人'}
    ];
    const load = async () => {
        $('#tableBox').innerHTML = '<div class="loading">加载中…</div>';
        try {
            // 期间留空表示查全部，避免默认只看本月而误以为历史凭证丢失
            const rows = await api('/finance/vouchers', {params: {
                period: $('#fPeriod').value, status: $('#fStatus').value,
                sourceType: $('#fSource').value, keyword: $('#kw').value.trim()
            }});
            $('#tableBox').innerHTML = tableHtml(cols, rows, r => {
                let s = `<button class="btn link" data-act="detail" data-id="${r.id}">详情</button>`;
                if (r.status === 'DRAFT') {
                    s += `<button class="btn link" data-act="post" data-id="${r.id}">审核过账</button>
                          <button class="btn link danger" data-act="del" data-id="${r.id}">删除</button>`;
                }
                return s;
            });
            $$('#tableBox [data-act]').forEach(b => b.onclick = async () => {
                const {act, id} = b.dataset;
                if (act === 'detail') return openVoucherDetail(id);
                if (act === 'del' && !confirm('确定删除这张草稿凭证吗？')) return;
                try {
                    await api(act === 'post' ? `/finance/vouchers/${id}/post` : `/finance/vouchers/${id}`,
                        {method: act === 'post' ? 'POST' : 'DELETE'});
                    toast(act === 'post' ? '已审核过账' : '已删除');
                    load();
                } catch (e) { toast(e.message, 'err'); }
            });
        } catch (e) {
            $('#tableBox').innerHTML = `<div class="empty">${esc(e.message)}</div>`;
        }
    };
    $('#btnSearch').onclick = load;
    ['fPeriod', 'fStatus', 'fSource'].forEach(id => $('#' + id).onchange = load);
    $('#kw').onkeydown = e => { if (e.key === 'Enter') load(); };
    $('#btnAdd').onclick = () => openVoucherForm(load);
    await load();
}

/** 凭证详情：主表信息 + 分录表格 + 借贷合计，还原纸质记账凭证的版式 */
async function openVoucherDetail(id) {
    try {
        const d = await api(`/finance/vouchers/${id}`);
        const entryRows = (d.entries || []).map(e => `
            <tr><td>${e.line_no}</td><td>${esc(e.summary)}</td>
                <td>${esc(e.subject_code)} ${esc(e.subject_name)}</td>
                <td class="num">${Number(e.debit) ? money(e.debit) : ''}</td>
                <td class="num">${Number(e.credit) ? money(e.credit) : ''}</td></tr>`).join('');
        openModal({
            title: `凭证 ${esc(d.voucher_no)}`,
            width: 760,
            body: `
              <div class="form-grid" style="margin-bottom:14px">
                <div class="form-item"><label>凭证日期</label><div class="input" style="border:none;background:none;padding-left:0">${esc(d.voucher_date)}</div></div>
                <div class="form-item"><label>会计期间</label><div class="input" style="border:none;background:none;padding-left:0">${esc(d.account_period)}</div></div>
                <div class="form-item"><label>来源</label><div class="input" style="border:none;background:none;padding-left:0">${SOURCE_MAP_HTML(d.source_type)}</div></div>
                <div class="form-item"><label>业务单号</label><div class="input" style="border:none;background:none;padding-left:0">${esc(d.biz_no || '-')}</div></div>
                <div class="form-item full"><label>摘要</label><div class="input" style="border:none;background:none;padding-left:0">${esc(d.summary)}</div></div>
              </div>
              <div class="tbl-wrap"><table class="tbl">
                <thead><tr><th>行</th><th>摘要</th><th>会计科目</th><th>借方</th><th>贷方</th></tr></thead>
                <tbody>${entryRows}
                  <tr style="font-weight:700;background:#f8fafc"><td colspan="3">合计</td>
                    <td class="num">${money(d.total_debit)}</td><td class="num">${money(d.total_credit)}</td></tr>
                </tbody></table></div>`
        });
    } catch (e) { toast(e.message, 'err'); }
}

/**
 * 手工凭证录入弹窗。
 * 复式记账的核心校验在前端先做一遍（实时显示借贷差额），后端 VoucherService
 * 还会再校验一次 —— 前端为了体验，后端为了数据正确性，两层都不能省。
 */
async function openVoucherForm(reload) {
    let subjects;
    try { subjects = await subjectOptions(); } catch (e) { return toast(e.message, 'err'); }
    const rowHtml = () => `
        <tr>
          <td><select class="select v-subject" style="width:100%;min-width:170px">${subjects}</select></td>
          <td><input class="input v-summary" style="width:100%"></td>
          <td><input class="input v-debit" type="number" min="0" step="0.01" value="0" style="width:110px"></td>
          <td><input class="input v-credit" type="number" min="0" step="0.01" value="0" style="width:110px"></td>
          <td><button class="btn link danger v-del">移除</button></td>
        </tr>`;
    openModal({
        title: '录入记账凭证', width: 860,
        body: `
          <div class="form-grid">
            <div class="form-item"><label>凭证日期</label><input class="input" id="vDate" type="date" value="${localDate()}"></div>
            <div class="form-item"><label>凭证字</label><select class="select" id="vWord">
              <option>记</option><option>收</option><option>付</option><option>转</option></select></div>
            <div class="form-item"><label>附件张数</label><input class="input" id="vAttach" type="number" min="0" value="0"></div>
            <div class="form-item full"><label>凭证摘要</label><input class="input" id="vSummary" placeholder="例如：支付 11 月办公室租金"></div>
          </div>
          <div class="toolbar" style="padding-left:0">
            <button class="btn" id="vAddRow">+ 添加分录</button>
            <span style="font-size:12px;color:#94a3b8">同一行只能填借方或贷方；借贷合计必须相等</span>
          </div>
          <div class="tbl-wrap"><table class="tbl">
            <thead><tr><th style="width:26%">会计科目</th><th>摘要</th><th style="width:130px">借方</th><th style="width:130px">贷方</th><th></th></tr></thead>
            <tbody id="vEntries">${rowHtml()}${rowHtml()}</tbody>
            <tfoot><tr style="font-weight:700;background:#f8fafc">
              <td colspan="2">合计</td><td id="vTotalD" class="num">¥0.00</td>
              <td id="vTotalC" class="num">¥0.00</td><td id="vDiff" style="color:#dc2626"></td>
            </tr></tfoot>
          </table></div>`,
        footer: modalFooter('保存草稿')
    });
    // 实时合计：任何一格金额变化都重算，差额不为 0 时给出红字提示
    const recalc = () => {
        let d = 0, c = 0;
        $$('#vEntries tr').forEach(tr => {
            d += Number($('.v-debit', tr).value || 0);
            c += Number($('.v-credit', tr).value || 0);
        });
        $('#vTotalD').textContent = money(d);
        $('#vTotalC').textContent = money(c);
        $('#vDiff').textContent = Math.abs(d - c) < 0.005 ? '✓ 平衡' : `差额 ${money(d - c)}`;
        $('#vDiff').style.color = Math.abs(d - c) < 0.005 ? '#16a34a' : '#dc2626';
        return {d, c};
    };
    const bindRows = () => {
        $$('#vEntries .v-del').forEach(b => b.onclick = () => {
            if ($$('#vEntries tr').length <= 2) return toast('至少保留两条分录', 'err');
            b.closest('tr').remove(); recalc();
        });
        $$('#vEntries input').forEach(i => i.oninput = recalc);
    };
    bindRows(); recalc();
    $('#vAddRow').onclick = () => {
        $('#vEntries').insertAdjacentHTML('beforeend', rowHtml());
        bindRows(); recalc();
    };
    $('#modalSubmit').onclick = async () => {
        const entries = [];
        $$('#vEntries tr').forEach(tr => {
            const debit = Number($('.v-debit', tr).value || 0);
            const credit = Number($('.v-credit', tr).value || 0);
            if (!debit && !credit) return; // 整行为空则忽略
            entries.push({
                subject_code: $('.v-subject', tr).value,
                summary: $('.v-summary', tr).value.trim(),
                debit, credit
            });
        });
        if (entries.length < 2) return toast('请至少填写两条有效分录', 'err');
        const t = recalc();
        if (Math.abs(t.d - t.c) >= 0.005) return toast('借贷不平衡，无法保存', 'err');
        const btn = $('#modalSubmit');
        btn.disabled = true;
        try {
            await api('/finance/vouchers', {method: 'POST', body: {
                voucher_date: $('#vDate').value, voucher_word: $('#vWord').value,
                summary: $('#vSummary').value.trim(), attachment_no: Number($('#vAttach').value || 0),
                entries
            }});
            toast('凭证草稿已保存'); closeModal(); reload();
        } catch (e) { toast(e.message, 'err'); btn.disabled = false; }
    };
}

/* ==================== 会计科目 ==================== */
async function renderSubjects() {
    const CATEGORY = {ASSET: '资产', LIABILITY: '负债', EQUITY: '权益', COST: '成本', INCOME: '收入', EXPENSE: '费用'};
    $('#content').innerHTML = `
        <div class="card">
          <div class="toolbar"><div class="spacer"></div>
            <button class="btn primary" id="btnAdd">+ 新增科目</button></div>
          <div id="tableBox"></div>
        </div>`;
    const cols = [
        {k: 'code', t: '科目编码'}, {k: 'name', t: '科目名称'},
        {k: 'category', t: '类别', type: 'html', render: r => esc(CATEGORY[r.category] || r.category)},
        {k: 'direction', t: '余额方向', type: 'html', render: r => r.direction === 'DEBIT' ? '借方' : '贷方'},
        {k: 'is_leaf', t: '末级', type: 'html', render: r => Number(r.is_leaf) === 1 ? '是' : '否'},
        {k: 'remark', t: '说明'}
    ];
    const load = async () => {
        $('#tableBox').innerHTML = tableHtml(cols, await api('/finance/subjects'));
    };
    $('#btnAdd').onclick = () => {
        openModal({
            title: '新增会计科目', width: 620,
            body: `<div class="form-grid">
              <div class="form-item"><label>科目编码<span class="req">*</span></label><input class="input" id="sCode" placeholder="如 100201"></div>
              <div class="form-item"><label>科目名称<span class="req">*</span></label><input class="input" id="sName" placeholder="如 银行存款-工行"></div>
              <div class="form-item"><label>科目类别</label><select class="select" id="sCat">
                ${Object.entries(CATEGORY).map(([k, v]) => `<option value="${k}">${v}</option>`).join('')}</select></div>
              <div class="form-item"><label>余额方向</label><select class="select" id="sDir">
                <option value="DEBIT">借方</option><option value="CREDIT">贷方</option></select></div>
              <div class="form-item"><label>上级科目编码</label><input class="input" id="sParent" placeholder="可留空"></div>
              <div class="form-item"><label>是否末级</label><select class="select" id="sLeaf">
                <option value="1">末级（可记账）</option><option value="0">非末级（汇总用）</option></select></div>
              <div class="form-item full"><label>说明</label><input class="input" id="sRemark"></div>
            </div>`,
            footer: modalFooter()
        });
        $('#modalSubmit').onclick = async () => {
            try {
                await api('/finance/subjects', {method: 'POST', body: {
                    code: $('#sCode').value.trim(), name: $('#sName').value.trim(),
                    category: $('#sCat').value, direction: $('#sDir').value,
                    parent_code: $('#sParent').value.trim(), is_leaf: Number($('#sLeaf').value),
                    remark: $('#sRemark').value.trim()
                }});
                toast('新增成功'); closeModal(); load();
            } catch (e) { toast(e.message, 'err'); }
        };
    };
    await load();
}

/* ==================== 科目余额表 / 明细账 ==================== */
async function renderLedger() {
    $('#content').innerHTML = `
        <div class="card">
          <div class="toolbar">
            <button class="btn primary tab-btn active" data-tab="balance">科目余额表</button>
            <button class="btn tab-btn" data-tab="detail">明细账</button>
            <div class="spacer"></div>
            <span style="font-size:12px;color:#94a3b8">仅统计已过账凭证</span>
          </div>
          <div id="ledgerBox"></div>
        </div>`;
    const subjects = await api('/finance/subjects');

    // 视图一：科目余额表（期初 / 本期借贷 / 期末）
    const showBalance = async () => {
        $('#ledgerBox').innerHTML = `
          <div class="toolbar" style="border-top:1px solid #f0f3f8">
            <label class="field-label">会计期间</label>
            <input id="lbPeriod" class="input" type="month" style="width:150px" value="${localPeriod()}">
            <button class="btn" id="lbQuery">查询</button>
          </div><div id="lbTable"></div>`;
        const load = async () => {
            $('#lbTable').innerHTML = '<div class="loading">加载中…</div>';
            try {
                const rows = await api('/finance/ledger/balance', {params: {period: $('#lbPeriod').value}});
                $('#lbTable').innerHTML = tableHtml([
                    {k: 'code', t: '科目编码'}, {k: 'name', t: '科目名称'},
                    {k: 'category', t: '类别'},
                    {k: 'opening_balance', t: '期初余额', type: 'money'},
                    {k: 'period_debit', t: '本期借方', type: 'money'},
                    {k: 'period_credit', t: '本期贷方', type: 'money'},
                    {k: 'closing_balance', t: '期末余额', type: 'money'}
                ], rows);
            } catch (e) { $('#lbTable').innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
        };
        $('#lbQuery').onclick = load;
        $('#lbPeriod').onchange = load;
        await load();
    };

    // 视图二：明细账（逐笔分录 + 滚动余额）
    const showDetail = async () => {
        $('#ledgerBox').innerHTML = `
          <div class="toolbar" style="border-top:1px solid #f0f3f8">
            <select id="ldSubject" class="select" style="width:230px">${optionHtml(subjects, 'code', s => `${s.code} ${s.name}`)}</select>
            <input id="ldFrom" class="input" type="date" style="width:150px" value="${localDate(new Date(new Date().getFullYear(), new Date().getMonth(), 1))}">
            <input id="ldTo" class="input" type="date" style="width:150px" value="${localDate()}">
            <button class="btn" id="ldQuery">查询</button>
          </div><div id="ldTable"></div>`;
        const load = async () => {
            $('#ldTable').innerHTML = '<div class="loading">加载中…</div>';
            try {
                const rows = await api('/finance/ledger/detail', {params: {
                    subjectCode: $('#ldSubject').value, from: $('#ldFrom').value, to: $('#ldTo').value
                }});
                $('#ldTable').innerHTML = tableHtml([
                    {k: 'voucher_date', t: '日期'}, {k: 'voucher_no', t: '凭证号'},
                    {k: 'summary', t: '摘要'}, {k: 'debit', t: '借方', type: 'money'},
                    {k: 'credit', t: '贷方', type: 'money'},
                    {k: 'balance', t: '余额', type: 'money'},
                    {k: 'biz_no', t: '业务单号'}
                ], rows);
            } catch (e) { $('#ldTable').innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
        };
        $('#ldQuery').onclick = load;
        await load();
    };

    bindTabs('#content', tab => tab === 'balance' ? showBalance() : showDetail());
    await showBalance();
}

/* ==================== 财务报表 ==================== */
async function renderReports() {
    $('#content').innerHTML = `
        <div class="card">
          <div class="toolbar">
            <label class="field-label">会计期间</label>
            <input id="rpPeriod" class="input" type="month" style="width:150px" value="${localPeriod()}">
            <button class="btn" id="rpQuery">生成报表</button>
            <div class="spacer"></div>
            <span style="font-size:12px;color:#94a3b8">取数口径：全部已过账凭证</span>
          </div>
          <div id="reportBox"></div>
        </div>`;
    const load = async () => {
        $('#reportBox').innerHTML = '<div class="loading">正在生成报表…</div>';
        try {
            const period = $('#rpPeriod').value;
            const [profit, balance] = await Promise.all([
                api('/finance/report/profit', {params: {period}}),
                api('/finance/report/balance', {params: {period}})
            ]);
            const balanced = Math.abs(Number(balance.difference)) < 0.005;
            $('#reportBox').innerHTML = `
              <div class="two-col">
                <div class="card" style="box-shadow:none;border:1px solid #eef2f7">
                  <div class="card-head"><div class="card-title">利润表 <small>${esc(profit.period)}</small></div></div>
                  ${tableHtml([{k: 'name', t: '项目'}, {k: 'current', t: '本期金额', type: 'money'},
                               {k: 'ytd', t: '本年累计', type: 'money'}], profit.rows)}
                </div>
                <div class="card" style="box-shadow:none;border:1px solid #eef2f7">
                  <div class="card-head"><div class="card-title">资产负债表 <small>${esc(balance.period)}</small></div>
                    <span class="badge ${balanced ? 'green' : 'red'}">${balanced ? '✓ 借贷平衡' : '⚠ 差额 ' + money(balance.difference)}</span>
                  </div>
                  <div class="card-body" style="padding:0">
                    <div style="font-weight:700;padding:10px 14px;background:#f8fafc">资产</div>
                    ${tableHtml([{k: 'code', t: '编码'}, {k: 'name', t: '项目'}, {k: 'amount', t: '金额', type: 'money'}], balance.assets)}
                    <div style="font-weight:700;padding:10px 14px;background:#f8fafc">负债</div>
                    ${tableHtml([{k: 'code', t: '编码'}, {k: 'name', t: '项目'}, {k: 'amount', t: '金额', type: 'money'}], balance.liabilities)}
                    <div style="font-weight:700;padding:10px 14px;background:#f8fafc">所有者权益</div>
                    ${tableHtml([{k: 'code', t: '编码'}, {k: 'name', t: '项目'}, {k: 'amount', t: '金额', type: 'money'}], balance.equities)}
                  </div>
                </div>
              </div>
              <div class="kpi-grid" style="grid-template-columns:repeat(4,1fr);margin-top:4px">
                <div class="kpi"><div class="kpi-label">资产合计</div><div class="kpi-value" style="font-size:20px">${money(balance.totalAssets)}</div></div>
                <div class="kpi"><div class="kpi-label">负债合计</div><div class="kpi-value" style="font-size:20px">${money(balance.totalLiability)}</div></div>
                <div class="kpi"><div class="kpi-label">权益合计</div><div class="kpi-value" style="font-size:20px">${money(balance.totalEquity)}</div></div>
                <div class="kpi"><div class="kpi-label">本期利润</div><div class="kpi-value" style="font-size:20px;color:${Number(profit.periodProfit) >= 0 ? '#dc2626' : '#16a34a'}">${money(profit.periodProfit)}</div></div>
              </div>`;
        } catch (e) {
            $('#reportBox').innerHTML = `<div class="empty">${esc(e.message)}</div>`;
        }
    };
    $('#rpQuery').onclick = load;
    await load();
}

/* ==================== 期末结账 ==================== */
async function renderPeriods() {
    $('#content').innerHTML = `
        <div class="card">
          <div class="toolbar"><div class="spacer"></div>
            <span style="font-size:12px;color:#94a3b8">结账后该期间禁止再新增或修改凭证</span></div>
          <div id="tableBox"></div>
        </div>`;
    const load = async () => {
        try {
            const rows = await api('/finance/periods');
            $('#tableBox').innerHTML = tableHtml([
                {k: 'account_period', t: '会计期间'},
                {k: 'voucher_count', t: '凭证张数'},
                {k: 'draft_count', t: '未过账草稿'},
                {k: 'status', t: '状态', type: 'html', render: r =>
                        r.status === 'CLOSED' ? '<span class="badge gray">已结账</span>' : '<span class="badge green">未结账</span>'},
                {k: 'closed_by', t: '结账人'}
            ], rows, r => r.status === 'CLOSED'
                ? `<button class="btn link" data-act="reopen" data-p="${r.account_period}">反结账</button>`
                : `<button class="btn link" data-act="close" data-p="${r.account_period}">结账</button>`);
            $$('#tableBox [data-act]').forEach(b => b.onclick = async () => {
                const reopen = b.dataset.act === 'reopen';
                if (!confirm(`确定${reopen ? '反结账' : '结账'} ${b.dataset.p} 吗？`)) return;
                try {
                    await api('/finance/periods/close', {method: 'POST', body: {period: b.dataset.p, reopen}});
                    toast(reopen ? '已反结账' : '结账成功'); load();
                } catch (e) { toast(e.message, 'err'); }
            });
        } catch (e) { $('#tableBox').innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
    };
    await load();
}

/* ==================== 应收应付 ==================== */
async function renderArap() {
    $('#content').innerHTML = `
        <div class="kpi-grid" id="arapKpi" style="grid-template-columns:repeat(6,1fr)"></div>
        <div class="card">
          <div class="toolbar">
            <select id="fType" class="select"><option value="">全部类型</option>
              <option value="AR">应收</option><option value="AP">应付</option></select>
            <select id="fStatus" class="select"><option value="">全部状态</option>
              <option value="OPEN">未结清</option><option value="PARTIAL">部分结清</option>
              <option value="CLOSED">已结清</option></select>
            <input id="kw" class="input w220" placeholder="搜索单号 / 往来单位">
            <button class="btn" id="btnSearch">查询</button>
          </div>
          <div id="tableBox"></div>
        </div>`;
    const loadKpi = async () => {
        const s = await api('/settle/summary');
        const cards = [
            ['应收总额', s.arTotal], ['已收金额', s.arSettled], ['未收余额', s.arOpen],
            ['应付总额', s.apTotal], ['已付金额', s.apSettled], ['未付余额', s.apOpen]
        ];
        $('#arapKpi').innerHTML = cards.map(([label, value]) => `
            <div class="kpi"><div class="kpi-label">${label}</div>
              <div class="kpi-value" style="font-size:19px">${money(value)}</div>
              <div class="kpi-hint">${esc(s.overdueBills ? `逾期单据 ${s.overdueBills} 笔` : '往来账实时汇总')}</div>
            </div>`).join('');
    };
    const cols = [
        {k: 'bill_no', t: '单据编号'},
        {k: 'bill_type', t: '类型', type: 'html', render: r => r.bill_type === 'AR'
                ? '<span class="badge blue">应收</span>' : '<span class="badge orange">应付</span>'},
        {k: 'partner_name', t: '往来单位'}, {k: 'biz_no', t: '来源单号'},
        {k: 'amount', t: '金额', type: 'money'},
        {k: 'settled_amount', t: '已核销', type: 'money'},
        {k: 'open_amount', t: '未核销', type: 'money'},
        {k: 'status', t: '状态', type: 'orderStatus'},
        {k: 'age_days', t: '账龄(天)'},
        {k: 'overdue', t: '逾期', type: 'html', render: r => r.overdue
                ? '<span class="badge red">逾期</span>' : '<span class="badge gray">正常</span>'},
        {k: 'bill_date', t: '单据日期'}
    ];
    const load = async () => {
        $('#tableBox').innerHTML = '<div class="loading">加载中…</div>';
        try {
            const rows = await api('/settle/arap', {params: {
                type: $('#fType').value, status: $('#fStatus').value, keyword: $('#kw').value.trim()
            }});
            $('#tableBox').innerHTML = tableHtml(cols, rows, r => r.status !== 'CLOSED'
                ? `<button class="btn link" data-act="settle" data-id="${r.id}">${r.bill_type === 'AR' ? '收款' : '付款'}核销</button>` : '');
            $$('#tableBox [data-act]').forEach(b => b.onclick = () => {
                const row = rows.find(x => String(x.id) === b.dataset.id);
                openSettleForm(row, () => { load(); loadKpi(); });
            });
        } catch (e) { $('#tableBox').innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
    };
    $('#btnSearch').onclick = load;
    $('#fType').onchange = load;
    $('#fStatus').onchange = load;
    $('#kw').onkeydown = e => { if (e.key === 'Enter') load(); };
    await Promise.all([loadKpi(), load()]);
}

/** 收付款核销弹窗：金额默认带出未结余额，不允许超额核销（后端也会再校验） */
function openSettleForm(row, reload) {
    const receive = row.bill_type === 'AR';
    openModal({
        title: (receive ? '收款' : '付款') + '核销 · ' + esc(row.bill_no), width: 600,
        body: `<div class="form-grid">
            <div class="form-item"><label>往来单位</label><div class="input" style="border:none;background:none;padding-left:0">${esc(row.partner_name)}</div></div>
            <div class="form-item"><label>未核销余额</label><div class="input" style="border:none;background:none;padding-left:0;color:#dc2626;font-weight:700">${money(row.open_amount)}</div></div>
            <div class="form-item"><label>核销金额<span class="req">*</span></label>
              <input class="input" id="stAmount" type="number" min="0.01" step="0.01" value="${Number(row.open_amount)}"></div>
            <div class="form-item"><label>结算方式</label><select class="select" id="stMethod">
              <option value="BANK">银行转账</option><option value="CASH">现金</option><option value="TRANSFER">票据</option></select></div>
            <div class="form-item"><label>结算日期</label><input class="input" id="stDate" type="date" value="${localDate()}"></div>
            <div class="form-item"><label>备注</label><input class="input" id="stRemark"></div>
          </div>
          <p style="font-size:12px;color:#64748b;margin:10px 0 0">核销将同步生成记账凭证：${receive ? '借 银行存款 / 贷 应收账款' : '借 应付账款 / 贷 银行存款'}。</p>`,
        footer: modalFooter('确认核销')
    });
    $('#modalSubmit').onclick = async () => {
        const amount = Number($('#stAmount').value);
        if (!(amount > 0)) return toast('核销金额必须大于 0', 'err');
        if (amount > Number(row.open_amount) + 0.001) return toast('核销金额不能超过未结余额', 'err');
        const btn = $('#modalSubmit');
        btn.disabled = true;
        try {
            await api('/settle/settle', {method: 'POST', body: {
                ar_ap_id: row.id, amount, pay_method: $('#stMethod').value,
                settle_date: $('#stDate').value, remark: $('#stRemark').value.trim()
            }});
            toast('核销成功，已生成凭证'); closeModal(); reload();
        } catch (e) { toast(e.message, 'err'); btn.disabled = false; }
    };
}

/* ==================== 收付款记录 ==================== */
async function renderSettlements() {
    $('#content').innerHTML = `
        <div class="card"><div class="toolbar">
          <input id="kw" class="input w220" placeholder="搜索单号 / 往来单位">
          <button class="btn" id="btnSearch">查询</button>
          <div class="spacer"></div><span style="font-size:12px;color:#94a3b8">每笔核销都对应一张凭证</span>
        </div><div id="tableBox"></div></div>`;
    const load = async () => {
        try {
            const rows = await api('/settle/records', {params: {keyword: $('#kw').value.trim()}});
            $('#tableBox').innerHTML = tableHtml([
                {k: 'settle_no', t: '单号'},
                {k: 'settle_type', t: '类型', type: 'html', render: r => r.settle_type === 'RECEIVE'
                        ? '<span class="badge green">收款</span>' : '<span class="badge orange">付款</span>'},
                {k: 'partner_name', t: '往来单位'}, {k: 'bill_no', t: '核销单据'},
                {k: 'amount', t: '金额', type: 'money'}, {k: 'pay_method', t: '结算方式'},
                {k: 'settle_date', t: '结算日期'}, {k: 'created_by', t: '经办人'},
                {k: 'remark', t: '备注'}
            ], rows);
        } catch (e) { $('#tableBox').innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
    };
    $('#btnSearch').onclick = load;
    $('#kw').onkeydown = e => { if (e.key === 'Enter') load(); };
    await load();
}

/* ==================== 账龄分析 ==================== */
async function renderAging() {
    $('#content').innerHTML = `
        <div class="card"><div class="toolbar">
          <select id="fType" class="select"><option value="">应收 + 应付</option>
            <option value="AR">仅应收</option><option value="AP">仅应付</option></select>
          <button class="btn" id="btnSearch">分析</button>
          <div class="spacer"></div><span style="font-size:12px;color:#94a3b8">按单据日期起算，仅统计未结清金额</span>
        </div><div id="tableBox"></div></div>`;
    const load = async () => {
        try {
            const rows = await api('/settle/aging', {params: {type: $('#fType').value}});
            $('#tableBox').innerHTML = tableHtml([
                {k: 'bucket', t: '账龄区间'}, {k: 'bills', t: '单据数'},
                {k: 'amount', t: '未结金额', type: 'money'}
            ], rows);
        } catch (e) { $('#tableBox').innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
    };
    $('#btnSearch').onclick = load;
    $('#fType').onchange = load;
    await load();
}

/* ==================== BOM 物料清单（金蝶「生产数据管理」） ==================== */
async function renderBoms() {
    $('#content').innerHTML = `
        <div class="card">
          <div class="toolbar">
            <input id="kw" class="input w220" placeholder="搜索 BOM 编码 / 成品">
            <button class="btn" id="btnSearch">查询</button>
            <div class="spacer"></div>
            <button class="btn primary" id="btnAdd">+ 新建 BOM</button>
          </div>
          <div id="tableBox"></div>
        </div>`;
    const cols = [
        {k: 'bom_code', t: 'BOM 编码'}, {k: 'product_name', t: '产成品'},
        {k: 'product_code', t: '成品编码'}, {k: 'version', t: '版本'},
        {k: 'item_count', t: '子件数'},
        {k: 'status', t: '状态', type: 'status'}
    ];
    const load = async () => {
        $('#tableBox').innerHTML = '<div class="loading">加载中…</div>';
        try {
            const rows = await api('/mf/boms', {params: {keyword: $('#kw').value.trim()}});
            $('#tableBox').innerHTML = tableHtml(cols, rows, r => `
                <button class="btn link" data-act="detail" data-id="${r.id}">查看子件</button>
                <button class="btn link danger" data-act="del" data-id="${r.id}">删除</button>`);
            $$('#tableBox [data-act]').forEach(b => b.onclick = async () => {
                if (b.dataset.act === 'detail') return openBomDetail(b.dataset.id);
                if (!confirm('确定删除这个 BOM 吗？')) return;
                try {
                    await api(`/mf/boms/${b.dataset.id}`, {method: 'DELETE'});
                    toast('删除成功'); load();
                } catch (e) { toast(e.message, 'err'); }
            });
        } catch (e) { $('#tableBox').innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
    };
    $('#btnSearch').onclick = load;
    $('#kw').onkeydown = e => { if (e.key === 'Enter') load(); };
    $('#btnAdd').onclick = () => openBomForm(load);
    await load();
}

async function openBomDetail(id) {
    try {
        const d = await api(`/mf/boms/${id}`);
        openModal({
            title: `BOM ${esc(d.bom_code)} · 子件清单`, width: 700,
            body: `<p style="font-size:13px;color:#64748b;margin:0 0 12px">
                    版本 ${esc(d.version)} · 共 ${(d.items || []).length} 种子件。
                    领料数量 = 单位用量 × 计划产量 × (1 + 损耗率)。</p>` +
                tableHtml([
                    {k: 'material_code', t: '子件编码'}, {k: 'material_name', t: '子件名称'},
                    {k: 'quantity', t: '单位用量'}, {k: 'unit', t: '单位'},
                    {k: 'loss_rate', t: '损耗率(%)'}, {k: 'cost_price', t: '成本价', type: 'money'}
                ], d.items || [])
        });
    } catch (e) { toast(e.message, 'err'); }
}

/** 新建 BOM：产成品 + 若干子件行（子件不能与成品相同，后端会再校验） */
async function openBomForm(reload) {
    await refreshOptions();
    const products = OPTIONS.products || [];
    const rowHtml = () => `<tr>
        <td><select class="select b-material" style="width:100%;min-width:200px">
          ${optionHtml(products, 'id', p => `${p.code} ${p.name}`)}</select></td>
        <td><input class="input b-qty" type="number" min="1" value="1" style="width:100px"></td>
        <td><input class="input b-loss" type="number" min="0" max="100" step="0.01" value="0" style="width:100px"></td>
        <td><button class="btn link danger b-del">移除</button></td></tr>`;
    openModal({
        title: '新建 BOM 物料清单', width: 720,
        body: `<div class="form-grid">
            <div class="form-item"><label>产成品<span class="req">*</span></label>
              <select class="select" id="bProduct">${optionHtml(products, 'id', p => `${p.code} ${p.name}`)}</select></div>
            <div class="form-item"><label>版本号</label><input class="input" id="bVersion" value="V1.0"></div>
            <div class="form-item full"><label>BOM 名称</label><input class="input" id="bName" value="标准 BOM"></div>
          </div>
          <div class="toolbar" style="padding-left:0"><button class="btn" id="bAddRow">+ 添加子件</button></div>
          <div class="tbl-wrap"><table class="tbl">
            <thead><tr><th>子件物料</th><th>单位用量</th><th>损耗率(%)</th><th></th></tr></thead>
            <tbody id="bItems">${rowHtml()}${rowHtml()}</tbody></table></div>`,
        footer: modalFooter()
    });
    const bindRows = () => $$('#bItems .b-del').forEach(b => b.onclick = () => {
        if ($$('#bItems tr').length <= 1) return toast('至少保留一个子件', 'err');
        b.closest('tr').remove();
    });
    bindRows();
    $('#bAddRow').onclick = () => { $('#bItems').insertAdjacentHTML('beforeend', rowHtml()); bindRows(); };
    $('#modalSubmit').onclick = async () => {
        const items = [];
        $$('#bItems tr').forEach(tr => items.push({
            material_id: Number($('.b-material', tr).value),
            quantity: Number($('.b-qty', tr).value || 0),
            loss_rate: Number($('.b-loss', tr).value || 0)
        }));
        const btn = $('#modalSubmit');
        btn.disabled = true;
        try {
            await api('/mf/boms', {method: 'POST', body: {
                product_id: Number($('#bProduct').value), version: $('#bVersion').value.trim(),
                name: $('#bName').value.trim(), items
            }});
            toast('BOM 创建成功'); closeModal(); reload();
        } catch (e) { toast(e.message, 'err'); btn.disabled = false; }
    };
}

/* ==================== 生产任务单（金蝶「生产任务管理」） ==================== */
async function renderMo() {
    $('#content').innerHTML = `
        <div class="card">
          <div class="toolbar">
            <select id="fStatus" class="select"><option value="">全部状态</option>
              ${Object.entries({DRAFT: '草稿', CONFIRMED: '已审核', ISSUED: '已领料', FINISHED: '已完工', CANCELLED: '已取消'})
                  .map(([k, v]) => `<option value="${k}">${v}</option>`).join('')}
            </select>
            <input id="kw" class="input w220" placeholder="搜索任务单号 / 成品">
            <button class="btn" id="btnSearch">查询</button>
            <div class="spacer"></div>
            <button class="btn primary" id="btnAdd">+ 新建生产任务</button>
          </div>
          <div id="tableBox"></div>
        </div>`;
    const cols = [
        {k: 'order_no', t: '任务单号'}, {k: 'product_name', t: '产成品'},
        {k: 'bom_code', t: 'BOM'}, {k: 'warehouse_name', t: '入库仓库'},
        {k: 'quantity', t: '计划数量'}, {k: 'finished_qty', t: '已完工'},
        {k: 'status', t: '状态', type: 'orderStatus'},
        {k: 'created_at', t: '创建时间', type: 'time'}
    ];
    const load = async () => {
        $('#tableBox').innerHTML = '<div class="loading">加载中…</div>';
        try {
            const rows = await api('/mf/orders', {params: {
                status: $('#fStatus').value, keyword: $('#kw').value.trim()
            }});
            $('#tableBox').innerHTML = tableHtml(cols, rows, r => {
                let s = `<button class="btn link" data-act="detail" data-id="${r.id}">明细</button>`;
                if (r.status === 'DRAFT') s += `<button class="btn link" data-act="confirm" data-id="${r.id}">审核</button>
                    <button class="btn link danger" data-act="cancel" data-id="${r.id}">取消</button>`;
                if (r.status === 'CONFIRMED') s += `<button class="btn link" data-act="issue" data-id="${r.id}">领料出库</button>
                    <button class="btn link danger" data-act="cancel" data-id="${r.id}">取消</button>`;
                if (r.status === 'ISSUED') s += `<button class="btn link" data-act="finish" data-id="${r.id}">完工入库</button>`;
                return s;
            });
            $$('#tableBox [data-act]').forEach(b => b.onclick = async () => {
                const {act, id} = b.dataset;
                if (act === 'detail') return openMoDetail(id);
                const tips = {issue: '确定按 BOM 领料出库吗？将扣减原材料库存。',
                    finish: '确定完工入库吗？将增加产成品库存并结转成本。'};
                if (tips[act] && !confirm(tips[act])) return;
                if (act === 'cancel' && !confirm('确定取消该生产任务吗？')) return;
                try {
                    await api(`/mf/orders/${id}/${act}`, {method: 'POST'});
                    toast('操作成功'); load();
                } catch (e) { toast(e.message, 'err'); }
            });
        } catch (e) { $('#tableBox').innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
    };
    $('#btnSearch').onclick = load;
    $('#fStatus').onchange = load;
    $('#kw').onkeydown = e => { if (e.key === 'Enter') load(); };
    $('#btnAdd').onclick = () => openMoForm(load);
    await load();
}

async function openMoDetail(id) {
    try {
        const d = await api(`/mf/orders/${id}`);
        openModal({
            title: `生产任务 ${esc(d.order_no)}`, width: 720,
            body: `<div class="form-grid" style="margin-bottom:12px">
                <div class="form-item"><label>计划数量</label><div class="input" style="border:none;background:none;padding-left:0">${d.quantity}</div></div>
                <div class="form-item"><label>状态</label><div class="input" style="border:none;background:none;padding-left:0">${badge(d.status, STATUS_MAP)}</div></div>
              </div>
              <p style="font-size:13px;color:#64748b;margin:0 0 10px">按 BOM 展开的应领物料（已计入损耗率）：</p>` +
                tableHtml([
                    {k: 'material_code', t: '子件编码'}, {k: 'material_name', t: '子件名称'},
                    {k: 'quantity', t: '单位用量'}, {k: 'loss_rate', t: '损耗率(%)'},
                    {k: 'plan_qty', t: '应领数量'}
                ], d.items || [])
        });
    } catch (e) { toast(e.message, 'err'); }
}

/** 新建生产任务：选成品后自动把 BOM 下拉过滤成该成品的版本 */
async function openMoForm(reload) {
    await refreshOptions();
    const products = OPTIONS.products || [];
    const boms = await api('/mf/boms');
    const bomsFor = (pid) => boms.filter(b => String(b.product_id) === String(pid));
    openModal({
        title: '新建生产任务', width: 640,
        body: `<div class="form-grid">
            <div class="form-item"><label>产成品<span class="req">*</span></label>
              <select class="select" id="mProduct">${optionHtml(products, 'id', p => `${p.code} ${p.name}`)}</select></div>
            <div class="form-item"><label>使用 BOM<span class="req">*</span></label>
              <select class="select" id="mBom"></select></div>
            <div class="form-item"><label>入库仓库<span class="req">*</span></label>
              <select class="select" id="mWarehouse">${optionHtml(OPTIONS.warehouses, 'id', w => `${w.code} ${w.name}`)}</select></div>
            <div class="form-item"><label>计划生产数量<span class="req">*</span></label>
              <input class="input" id="mQty" type="number" min="1" value="10"></div>
            <div class="form-item full"><label>备注</label><input class="input" id="mRemark"></div>
          </div>
          <p id="mHint" style="font-size:12px;color:#94a3b8;margin:8px 0 0"></p>`,
        footer: modalFooter()
    });
    const syncBoms = () => {
        const list = bomsFor($('#mProduct').value);
        $('#mBom').innerHTML = optionHtml(list, 'id', b => `${b.bom_code} ${b.version} · ${b.item_count} 个子件`);
        $('#mHint').textContent = list.length
            ? '领料时将按此 BOM 自动展开子件并扣减库存。'
            : '该成品尚未维护 BOM，请先在「BOM 物料清单」页面创建。';
    };
    $('#mProduct').onchange = syncBoms;
    syncBoms();
    $('#modalSubmit').onclick = async () => {
        if (!$('#mBom').value) return toast('请先为该成品创建 BOM', 'err');
        const btn = $('#modalSubmit');
        btn.disabled = true;
        try {
            await api('/mf/orders', {method: 'POST', body: {
                product_id: Number($('#mProduct').value), bom_id: Number($('#mBom').value),
                warehouse_id: Number($('#mWarehouse').value), quantity: Number($('#mQty').value),
                remark: $('#mRemark').value.trim()
            }});
            toast('生产任务已创建'); closeModal(); reload();
        } catch (e) { toast(e.message, 'err'); btn.disabled = false; }
    };
}

/* ==================== 用户与权限（金蝶「系统管理」） ==================== */
async function renderUsers() {
    $('#content').innerHTML = `
        <div class="card">
          <div class="toolbar">
            <input id="kw" class="input w220" placeholder="搜索用户名 / 姓名">
            <button class="btn" id="btnSearch">查询</button>
            <div class="spacer"></div>
            <button class="btn primary" id="btnAdd">+ 新增用户</button>
          </div>
          <div id="tableBox"></div>
        </div>`;
    const roles = await api('/system/roles');
    const roleName = (code) => (roles.find(r => r.code === code) || {}).name || code;
    const cols = [
        {k: 'username', t: '用户名'}, {k: 'real_name', t: '姓名'},
        {k: 'role', t: '角色', type: 'html', render: r =>
                `<span class="badge ${r.role === 'ADMIN' ? 'blue' : 'gray'}">${esc(roleName(r.role))}</span>`},
        {k: 'status', t: '状态', type: 'status'},
        {k: 'created_at', t: '创建时间', type: 'time'}
    ];
    const load = async () => {
        try {
            const rows = await api('/system/users', {params: {keyword: $('#kw').value.trim()}});
            $('#tableBox').innerHTML = tableHtml(cols, rows, r => `
                <button class="btn link" data-act="role" data-id="${r.id}" data-role="${r.role}">改角色</button>
                <button class="btn link" data-act="pwd" data-id="${r.id}">重置密码</button>
                <button class="btn link ${Number(r.status) === 1 ? 'danger' : ''}" data-act="status"
                        data-id="${r.id}" data-status="${Number(r.status) === 1 ? 0 : 1}">
                    ${Number(r.status) === 1 ? '停用' : '启用'}</button>`);
            $$('#tableBox [data-act]').forEach(b => b.onclick = async () => {
                const id = b.dataset.id, act = b.dataset.act;
                try {
                    if (act === 'role') return openRoleForm(id, b.dataset.role, roles, load);
                    if (act === 'pwd') {
                        const pwd = prompt('请输入新密码（至少 6 位）');
                        if (!pwd) return;
                        await api(`/system/users/${id}/password`, {method: 'PUT', body: {password: pwd}});
                    } else {
                        if (!confirm(`确定${Number(b.dataset.status) === 1 ? '启用' : '停用'}该账号吗？`)) return;
                        await api(`/system/users/${id}/status`, {method: 'PUT', body: {status: Number(b.dataset.status)}});
                    }
                    toast('操作成功'); load();
                } catch (e) { toast(e.message, 'err'); }
            });
        } catch (e) { $('#tableBox').innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
    };
    $('#btnSearch').onclick = load;
    $('#kw').onkeydown = e => { if (e.key === 'Enter') load(); };
    $('#btnAdd').onclick = () => {
        openModal({
            title: '新增用户', width: 600,
            body: `<div class="form-grid">
              <div class="form-item"><label>用户名<span class="req">*</span></label><input class="input" id="uName"></div>
              <div class="form-item"><label>姓名</label><input class="input" id="uReal"></div>
              <div class="form-item"><label>密码<span class="req">*</span></label><input class="input" id="uPwd" type="password" placeholder="至少 6 位"></div>
              <div class="form-item"><label>角色</label><select class="select" id="uRole">
                ${optionHtml(roles, 'code', r => `${r.name} (${r.code})`)}</select></div>
            </div>
            <p style="font-size:12px;color:#94a3b8;margin:10px 0 0">角色决定菜单可见范围与接口权限，重新登录后生效。</p>`,
            footer: modalFooter()
        });
        $('#modalSubmit').onclick = async () => {
            try {
                await api('/system/users', {method: 'POST', body: {
                    username: $('#uName').value.trim(), real_name: $('#uReal').value.trim(),
                    password: $('#uPwd').value, role: $('#uRole').value
                }});
                toast('用户创建成功'); closeModal(); load();
            } catch (e) { toast(e.message, 'err'); }
        };
    };
    await load();
}

function openRoleForm(id, currentRole, roles, reload) {
    openModal({
        title: '调整用户角色', width: 560,
        body: `<div class="form-grid">
            <div class="form-item full"><label>选择角色</label>
              <select class="select" id="rRole">${optionHtml(roles, 'code', r => `${r.name} (${r.code})`, currentRole)}</select></div>
          </div>
          <div id="rMenus" style="margin-top:12px;font-size:12px;color:#64748b"></div>`,
        footer: modalFooter('保存')
    });
    const showMenus = () => {
        const r = roles.find(x => x.code === $('#rRole').value);
        $('#rMenus').textContent = r
            ? (r.menus.includes('*') ? '该角色可访问全部模块' : `该角色可访问 ${r.menus.length} 个页面`)
            : '';
    };
    $('#rRole').onchange = showMenus;
    showMenus();
    $('#modalSubmit').onclick = async () => {
        try {
            await api(`/system/users/${id}/role`, {method: 'PUT', body: {role: $('#rRole').value}});
            toast('角色已更新，重新登录后生效'); closeModal(); reload();
        } catch (e) { toast(e.message, 'err'); }
    };
}
