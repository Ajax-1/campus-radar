// 网申填表逻辑测试。表单结构仿照 Ant Design、飞书招聘、Moka 常见写法构造，不是真实页面快照。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { JSDOM } = require('jsdom');
const { fillForm, guessJob } = require('../extension/fill.js');

const PROFILE = {
  name: '张三', phone: '13800000000', email: 'zhangsan@example.com', school: '北京大学',
  major: '计算机科学与技术', degree: '硕士', eduStart: '2024-09', eduEnd: '2027-06', gpa: '3.8/4.0',
  english: 'CET-6 580', github: 'https://github.com/zhangsan', intro: '热爱后端开发'
};

function dom(html, url = 'https://nio.jobs.feishu.cn/campus/position/1/apply') {
  return new JSDOM(`<!doctype html><html><head><title>后端开发工程师 - 蔚来校招</title></head><body>${html}</body></html>`, { url });
}

// 模拟 React 受控组件：只有收到 input 事件才更新「组件状态」
function trackReactState(doc) {
  const state = {};
  doc.querySelectorAll('input, textarea').forEach(el => {
    el.addEventListener('input', e => { state[e.target.name || e.target.id] = e.target.value; });
  });
  return state;
}

test('Ant Design 表单：按表单项里的标签识别并触发 input 事件', () => {
  const { window } = dom(`
    <form>
      <div class="ant-form-item"><div class="ant-form-item-label"><label>* 姓名</label></div><input name="n1"></div>
      <div class="ant-form-item"><div class="ant-form-item-label"><label>手机号码</label></div><input name="n2"></div>
      <div class="ant-form-item"><div class="ant-form-item-label"><label>电子邮箱</label></div><input name="n3"></div>
      <div class="ant-form-item"><div class="ant-form-item-label"><label>毕业院校</label></div><input name="n4"></div>
      <div class="ant-form-item"><div class="ant-form-item-label"><label>自我评价</label></div><textarea name="n5"></textarea></div>
      <button type="submit">提交</button>
    </form>`);
  const doc = window.document;
  const state = trackReactState(doc);
  let submitted = false;
  doc.querySelector('form').addEventListener('submit', () => { submitted = true; });

  const r = fillForm(doc, PROFILE);
  assert.deepEqual(r.filled.map(f => f.key), ['name', 'phone', 'email', 'school', 'intro']);
  assert.equal(state.n1, '张三');
  assert.equal(state.n2, '13800000000');
  assert.equal(state.n5, '热爱后端开发');
  assert.equal(submitted, false, '绝不能自动提交');
});

test('label[for]、placeholder、aria-label 都能识别', () => {
  const { window } = dom(`
    <label for="major">专业</label><input id="major">
    <input placeholder="请输入GPA">
    <input aria-label="GitHub 主页">`);
  const r = fillForm(window.document, PROFILE);
  assert.deepEqual(r.filled.map(f => f.key), ['major', 'gpa', 'github']);
  assert.equal(window.document.getElementById('major').value, '计算机科学与技术');
});

test('已经填了的字段保留用户的值', () => {
  const { window } = dom(`
    <div class="ant-form-item"><label>姓名</label><input name="a" value="李四"></div>
    <div class="ant-form-item"><label>邮箱</label><input name="b"></div>`);
  const r = fillForm(window.document, PROFILE);
  assert.equal(window.document.querySelector('[name=a]').value, '李四');
  assert.deepEqual(r.keptExisting, ['姓名']);
  assert.deepEqual(r.filled.map(f => f.key), ['email']);
});

test('紧急联系人电话、联系人姓名不会被误填', () => {
  const { window } = dom(`
    <div class="ant-form-item"><label>紧急联系人姓名</label><input name="c1"></div>
    <div class="ant-form-item"><label>紧急联系人电话</label><input name="c2"></div>
    <div class="ant-form-item"><label>手机</label><input name="p"></div>`);
  const r = fillForm(window.document, PROFILE);
  assert.equal(window.document.querySelector('[name=c1]').value, '');
  assert.equal(window.document.querySelector('[name=c2]').value, '');
  assert.equal(window.document.querySelector('[name=p]').value, '13800000000');
  assert.deepEqual(r.unmatched, ['紧急联系人姓名', '紧急联系人电话']);
});

test('原生下拉框按选项文字选中；组件库下拉框交给用户', () => {
  const { window } = dom(`
    <div class="form-item"><label>最高学历</label>
      <select name="deg"><option value="">请选择</option><option value="1">本科</option><option value="2">硕士研究生</option></select></div>
    <div class="ant-form-item"><label>英语水平</label>
      <div class="ant-select"><input role="combobox" readonly name="eng"></div></div>`);
  const r = fillForm(window.document, PROFILE);
  assert.equal(window.document.querySelector('[name=deg]').value, '2');
  assert.deepEqual(r.needsManual, ['英语水平']);
});

test('month / date 类型的毕业时间按格式填写', () => {
  const { window } = dom(`
    <label for="s">入学时间</label><input id="s" type="month">
    <label for="e">毕业时间</label><input id="e" type="date">`);
  fillForm(window.document, PROFILE);
  assert.equal(window.document.getElementById('s').value, '2024-09');
  assert.equal(window.document.getElementById('e').value, '2027-06-01');
});

test('隐藏、禁用、密码、文件框都跳过', () => {
  const { window } = dom(`
    <label>姓名<input name="h" style="display:none"></label>
    <label>邮箱<input name="d" disabled></label>
    <label>密码<input type="password" name="pw"></label>
    <label>简历附件<input type="file" name="f"></label>`);
  const r = fillForm(window.document, PROFILE);
  assert.equal(r.filled.length, 0);
});

test('从站点和标题推断公司和岗位', () => {
  const { window } = dom('<h1>后端开发工程师</h1>');
  assert.deepEqual(guessJob(window.document, 'nio.jobs.feishu.cn'),
    { company: '蔚来', title: '后端开发工程师', url: 'https://nio.jobs.feishu.cn/campus/position/1/apply' });

  const other = new JSDOM('<!doctype html><title>Java 开发 - 米哈游校园招聘</title><body></body>', { url: 'https://jobs.mihoyo.com/x' });
  assert.equal(guessJob(other.window.document, 'jobs.mihoyo.com').company, '米哈游');
});
