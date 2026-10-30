// 网申表单自动填写的核心逻辑。内容脚本和 Node 测试共用这一个文件，不依赖浏览器扩展 API。
// 原则：只填空着的字段、从不点提交、填不了的明确告诉用户。
(function (root) {
  'use strict';

  // 字段识别规则：按顺序匹配，先匹配到的生效；exclude 用来排除「紧急联系人电话」这类容易误中的字段
  const FIELDS = [
    { key: 'name', label: '姓名', match: /^(真实)?姓名|^名字|^your name$|^full ?name|^name$/i, exclude: /紧急|联系人|英文|拼音|学校|公司|项目|证书/ },
    { key: 'englishName', label: '英文名', match: /英文名|拼音|english name/i },
    { key: 'phone', label: '手机号', match: /手机|电话|mobile|phone|tel/i, exclude: /紧急|联系人|家庭|座机/ },
    { key: 'email', label: '邮箱', match: /邮箱|e-?mail|电子邮件/i, exclude: /紧急|联系人|导师/ },
    { key: 'wechat', label: '微信', match: /微信|wechat/i },
    { key: 'currentCity', label: '现居城市', match: /现居|所在城市|居住地|current city|location/i },
    { key: 'school', label: '学校', match: /学校|院校|毕业院校|university|school|college/i, exclude: /高中|类型|层次|性质/ },
    { key: 'major', label: '专业', match: /专业|major|field of study/i, exclude: /排名|方向|技能|证书|资格/ },
    { key: 'degree', label: '学历', match: /学历|学位|degree|education level/i, exclude: /证书|编号/ },
    { key: 'eduStart', label: '入学时间', match: /入学|开始时间|start (date|time)/i },
    { key: 'eduEnd', label: '毕业时间', match: /毕业时间|毕业年月|毕业日期|结束时间|graduation|end (date|time)/i },
    { key: 'gpa', label: 'GPA', match: /gpa|绩点|平均分/i },
    { key: 'rank', label: '专业排名', match: /排名|rank/i },
    { key: 'english', label: '英语水平', match: /英语|cet|托福|雅思|toefl|ielts|english/i },
    { key: 'github', label: 'GitHub', match: /github/i },
    { key: 'website', label: '个人主页', match: /个人主页|个人网站|博客|作品集|portfolio|website|blog/i },
    { key: 'intro', label: '自我评价', match: /自我评价|自我介绍|个人简介|个人优势|about (me|yourself)|self/i }
  ];

  const SKIP_TYPES = new Set(['hidden', 'password', 'file', 'checkbox', 'radio', 'submit', 'button', 'image', 'reset']);

  function clean(text) {
    return (text || '').replace(/[\s*：:＊]+/g, ' ').trim();
  }

  // 找一个输入框对应的「标签文字」：label[for] → 包裹的 label → aria-label → 表单项容器里的标签 → placeholder
  function labelOf(el) {
    const doc = el.ownerDocument;
    if (el.id) {
      const byFor = doc.querySelector('label[for="' + cssEscape(el.id) + '"]');
      if (byFor && clean(byFor.textContent)) return clean(byFor.textContent);
    }
    const wrapping = el.closest('label');
    if (wrapping && clean(wrapping.textContent)) return clean(wrapping.textContent);
    const aria = el.getAttribute('aria-label');
    if (aria && clean(aria)) return clean(aria);
    const labelledBy = el.getAttribute('aria-labelledby');
    if (labelledBy) {
      const ref = doc.getElementById(labelledBy);
      if (ref && clean(ref.textContent)) return clean(ref.textContent);
    }
    // Ant Design、飞书招聘、Moka 等常见写法：同一个表单项容器里有一个标签元素
    const item = el.closest('.ant-form-item, .ant-row, [class*="form-item"], [class*="formItem"], [class*="field"], .el-form-item, li, tr');
    if (item) {
      const labelEl = item.querySelector('label, .ant-form-item-label, [class*="label"], [class*="Label"], th');
      if (labelEl && labelEl !== el && !labelEl.contains(el) && clean(labelEl.textContent)) return clean(labelEl.textContent);
    }
    const ph = el.getAttribute('placeholder');
    if (ph && clean(ph)) return clean(ph);
    return el.getAttribute('name') || '';
  }

  function cssEscape(s) {
    return String(s).replace(/["\\]/g, '\\$&');
  }

  function fieldFor(label) {
    for (const f of FIELDS) {
      if (f.match.test(label) && !(f.exclude && f.exclude.test(label))) return f;
    }
    return null;
  }

  // 组件库下拉框：只读 input + 弹层，靠脚本选不可靠
  function isWidgetSelect(el) {
    return el.tagName !== 'SELECT'
      && (el.getAttribute('role') === 'combobox' || !!el.closest('.ant-select, .el-select, [class*="select"]'));
  }

  function isVisible(el) {
    if (el.disabled) return false;
    const style = el.ownerDocument.defaultView.getComputedStyle(el);
    return style.display !== 'none' && style.visibility !== 'hidden';
  }

  // React / Vue 受控组件只认原生 setter + input 事件，直接赋值 el.value 页面状态不会更新
  function setNativeValue(el, value) {
    const win = el.ownerDocument.defaultView;
    const proto = el.tagName === 'TEXTAREA' ? win.HTMLTextAreaElement.prototype : win.HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
    setter.call(el, value);
    el.dispatchEvent(new win.Event('input', { bubbles: true }));
    el.dispatchEvent(new win.Event('change', { bubbles: true }));
    el.dispatchEvent(new win.Event('blur', { bubbles: true }));
  }

  function fillSelect(el, value) {
    const want = String(value).toLowerCase();
    const option = Array.from(el.options).find(o => o.text.toLowerCase().includes(want) || o.value.toLowerCase() === want);
    if (!option) return false;
    el.value = option.value;
    el.dispatchEvent(new el.ownerDocument.defaultView.Event('change', { bubbles: true }));
    return true;
  }

  // 日期输入框按类型转换格式：type=month 要 2023-09，type=date 要 2023-09-01，普通文本原样
  function formatFor(el, key, value) {
    if ((key === 'eduStart' || key === 'eduEnd') && /^\d{4}-\d{2}$/.test(value)) {
      if (el.type === 'date') return value + '-01';
      if (el.type === 'month') return value;
    }
    return value;
  }

  /**
   * @param doc 要填写的文档
   * @param profile 本地保存的个人信息，键见 FIELDS
   * @returns {{filled: Array<{key,label,field}>, keptExisting: string[], unmatched: string[], needsManual: string[]}}
   */
  function fillForm(doc, profile) {
    const report = { filled: [], keptExisting: [], unmatched: [], needsManual: [] };
    const used = new Set();
    const elements = Array.from(doc.querySelectorAll('input, textarea, select'));
    for (const el of elements) {
      if (el.tagName === 'INPUT' && SKIP_TYPES.has((el.type || 'text').toLowerCase())) continue;
      if (!isVisible(el)) continue;
      // 普通只读框（如自动生成的编号）跳过；只读的下拉组件留给后面报告「需要手动选择」
      if (el.readOnly && !isWidgetSelect(el)) continue;
      const label = labelOf(el);
      if (!label) continue;
      const field = fieldFor(label);
      if (!field) {
        report.unmatched.push(label);
        continue;
      }
      const value = profile[field.key];
      if (value === undefined || value === null || String(value).trim() === '') continue;
      // 同一个字段只填第一个（避免把手机号也填进「紧急联系人」之类漏网的框）；个人简介类可以出现多次
      if (used.has(field.key) && field.key !== 'intro') continue;
      if (el.tagName !== 'SELECT' && el.value && el.value.trim() !== '') {
        report.keptExisting.push(label);
        used.add(field.key);
        continue;
      }
      if (el.tagName === 'SELECT') {
        if (!fillSelect(el, value)) {
          report.needsManual.push(label);
          continue;
        }
      } else if (isWidgetSelect(el)) {
        // 组件库的下拉框通常是只读 input + 弹层，没法可靠地自动选，交给用户
        report.needsManual.push(label);
        continue;
      } else {
        setNativeValue(el, formatFor(el, field.key, String(value)));
      }
      used.add(field.key);
      el.setAttribute('data-campus-radar', 'filled');
      el.style.outline = '2px solid #34c759';
      report.filled.push({ key: field.key, label: field.label, field: label });
    }
    return report;
  }

  // 招聘站点 → 公司名，记录投递时用；没收录的站点用页面标题
  const HOST_COMPANY = [
    [/^jobs\.bytedance\.com$/, '字节跳动'],
    [/^xiaopeng\.jobs\.feishu\.cn$/, '小鹏汽车'],
    [/^nio\.jobs\.feishu\.cn$/, '蔚来'],
    [/^thundersoft\.jobs\.feishu\.cn$/, '中科创达'],
    [/^arashivision\.jobs\.feishu\.cn$/, '影石Insta360'],
    [/^modelbest\.jobs\.feishu\.cn$/, '面壁智能'],
    [/^yesv-desaysv\.jobs\.feishu\.cn$/, '德赛西威'],
    [/join\.qq\.com$/, '腾讯'],
    [/alibaba\.com$/, '阿里巴巴'],
    [/meituan\.com$/, '美团'],
    [/baidu\.com$/, '百度'],
    [/jd\.com$/, '京东'],
    [/kuaishou\.cn$/, '快手'],
    [/pddglobalhr\.com$/, '拼多多']
  ];

  // 页面标题常见格式：「后端开发工程师 - 蔚来校招」「加入小鹏集团」
  function guessJob(doc, hostname) {
    let company = null;
    for (const [re, name] of HOST_COMPANY) {
      if (re.test(hostname)) { company = name; break; }
    }
    const title = (doc.title || '').trim();
    const parts = title.split(/\s+[-|–—]\s+/);
    if (!company && parts.length > 1) company = parts[parts.length - 1].replace(/(校园招聘|校招|招聘|官网)$/g, '').trim();
    const h1 = doc.querySelector('h1, [class*="position-title"], [class*="job-title"], [class*="jobTitle"]');
    const jobTitle = (h1 && h1.textContent.trim()) || (parts.length > 1 ? parts[0] : null);
    return { company: company || null, title: jobTitle || null, url: doc.location ? doc.location.href : null };
  }

  const api = { fillForm, labelOf, fieldFor, guessJob, FIELDS };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.CampusRadarFill = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
