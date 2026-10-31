// 读脚本前置脚本的测试台:用一套极简的假 DOM(只有测试用到的接口)装上前置脚本,逐条跑用例,把结果按 JSON 打印出来。
// 用法:node harness.js <前置脚本> <用例 JSON>;用例为 [{name, code, args?, resultChars?, page?}]。
'use strict';
const fs = require('fs');
const prelude = fs.readFileSync(process.argv[2], 'utf8');
const cases = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
const write = process.stdout.write.bind(process.stdout);
const stringify = JSON.stringify;
// 浏览器原生的查找方法把选择器变成字符串时不经页面里的全局 String,假 DOM 也先存一份
const asString = String;

const log = { mutations: 0, clicks: 0, navigations: 0, pageCode: 0, requests: [] };

// 假 DOM 的一套接口。同源 iframe 里的节点在浏览器里属于那个框架自己的一套接口(instanceof 认不出主框架的类,方法也是另一份),
// 所以做成函数:主文档和 iframe 里的文档各用一套
const makeDom = () => {
  class EventTarget {}
  class Node extends EventTarget {
    constructor() { super(); this._kids = []; this._attrs = {}; this.parentNode = null; this._text = null; }
    // 和浏览器一样只有 Node 上一个 getter,对哪种节点都给对的值
    get nodeType() { return this._nodeType; }
    get parentNode() { return this._parent || null; }
    set parentNode(v) { this._parent = v; }
    get firstChild() { return this._kids[0] || null; }
    get childNodes() { return new NodeList(this._kids); }
    get textContent() { return this._text != null ? this._text : this._kids.map((k) => k.textContent).join(''); }
    set textContent(v) { log.mutations++; this._text = String(v); this._kids = []; }
    appendChild(c) { log.mutations++; this._kids.push(c); c.parentNode = this; return c; }
    removeChild(c) { log.mutations++; this._kids = this._kids.filter((k) => k !== c); return c; }
    contains(o) { return o === this || this._kids.some((k) => k.contains(o)); }
    hasChildNodes() { return this._kids.length > 0; }
    _all() { const out = []; for (const k of this._kids) { out.push(k); out.push(...k._all()); } return out; }
    _match(sel) { return this._all().filter((e) => e._is(sel)); }
  }
  class CharacterData extends Node {
    constructor(text) { super(); this._nodeType = 3; this._text = text; }
    get data() { return this._text; }
    get nodeValue() { return this._text; }
    get length() { return this._text.length; }
    substringData(start, count) { return this._text.slice(start, start + count); }
    _copy() { return new Text(this._text); }
    get outerHTML() { return this._text; }
    _is() { return false; }
  }
  class Text extends CharacterData { get wholeText() { return this._text; } }
  // 和浏览器一样,查找方法在 Element、Document、DocumentFragment 上,不在 Node 上;选择器参数和浏览器一样先变成字符串
  const finders = {
    querySelectorAll(sel) { return new NodeList(this._match(asString(sel))); },
    querySelector(sel) { return this._match(asString(sel))[0] || null; },
  };
  class Element extends Node {
    constructor(tag, attrs, text) { super(); this._nodeType = 1; this.tagName = tag.toUpperCase(); Object.assign(this._attrs, attrs || {}); if (text != null) this._text = text; }
    get localName() { return this.tagName.toLowerCase(); }
    get shadowRoot() { return this._shadow || null; }
    _is(sel) {
      for (const part of sel.split(',').map((s) => s.trim())) {
        const m = /^([a-z*]+)?(?:\[([a-z-]+)(?:([\^]?=)([^\]]*))?\])?$/.exec(part);
        if (!m) throw new Error('bad selector ' + part);
        if (m[1] && m[1] !== '*' && m[1] !== this.tagName.toLowerCase()) continue;
        if (m[2] && !(m[2] in this._attrs)) continue;
        return true;
      }
      return false;
    }
    getAttribute(n) { return n in this._attrs ? this._attrs[n] : null; }
    setAttribute(n, v) { log.mutations++; this._attrs[n] = String(v); }
    hasAttribute(n) { return n in this._attrs; }
    click() { log.clicks++; }
    focus() { log.mutations++; }
    // 在页面文档里复制:和浏览器一样经构造器(自定义元素会跑页面代码)
    cloneNode(deep) {
      const C = this.constructor;
      const c = new C(this.tagName.toLowerCase(), Object.assign({}, this._attrs), this._text);
      if (deep) for (const k of this._kids) { const kc = k.cloneNode(true); c._kids.push(kc); kc.parentNode = c; }
      return c;
    }
    // 复制进不属于任何窗口的空文档:不经构造器
    _copy(deep) {
      const c = Object.create(Object.getPrototypeOf(this));
      Object.assign(c, { _kids: [], _attrs: Object.assign({}, this._attrs), parentNode: null, _text: this._text, _nodeType: 1, tagName: this.tagName });
      if (deep) for (const k of this._kids) { const kc = k._copy(true); c._kids.push(kc); kc.parentNode = c; }
      return c;
    }
    get children() { return new NodeList(this._kids); }
    get attributes() { return new NamedNodeMap(this); }
    get innerHTML() { return this._text != null ? this._text : this._kids.map((k) => k.outerHTML).join(''); }
    set innerHTML(v) { log.mutations++; this._text = String(v); }
    get outerHTML() {
      const t = this.tagName.toLowerCase();
      const a = Object.keys(this._attrs).map((k) => ' ' + k + '="' + this._attrs[k] + '"').join('');
      return '<' + t + a + '>' + this.innerHTML + '</' + t + '>';
    }
  }
  Object.assign(Element.prototype, finders);
  class HTMLElement extends Element {
    // 不排版时浏览器给的就是子孙的文字
    get innerText() { return this.textContent; }
    get outerText() { return this.textContent; }
  }
  class HTMLInputElement extends HTMLElement {
    get type() { return this._attrs.type || 'text'; }
    get value() { return this._value != null ? this._value : (this._attrs.value || ''); }
    set value(v) { log.mutations++; this._value = String(v); }
    get name() { return this._attrs.name || ''; }
    get valueAsNumber() { return this.type === 'number' ? Number(this.value) : NaN; }
    get selectionStart() { return this.value.length; }
    get selectionEnd() { return this.value.length; }
  }
  class HTMLTextAreaElement extends HTMLElement {
    get value() { return this._value != null ? this._value : this.textContent; }
    set value(v) { log.mutations++; this._value = String(v); }
    get name() { return this._attrs.name || ''; }
    get textLength() { return this.value.length; }
  }
  class HTMLIFrameElement extends HTMLElement {
    get contentDocument() { return this._doc || null; }
  }
  // template 的子节点在 content(一个 DocumentFragment)里:querySelectorAll 找不进去,innerHTML 照样写出来;深复制时一起复制
  class HTMLTemplateElement extends HTMLElement {
    constructor(...a) { super(...a); this._content = new DocumentFragment(); }
    get content() { return this._content; }
    get innerHTML() { return this._content._kids.map((k) => k.outerHTML).join(''); }
    cloneNode(deep) {
      const c = super.cloneNode(deep);
      if (deep) for (const k of this._content._kids) { const kc = k.cloneNode(true); c._content._kids.push(kc); kc.parentNode = c._content; }
      return c;
    }
    _copy(deep) {
      const c = super._copy(deep);
      c._content = new DocumentFragment();
      if (deep) for (const k of this._content._kids) { const kc = k._copy(true); c._content._kids.push(kc); kc.parentNode = c._content; }
      return c;
    }
  }
  class Attr extends Node {
    constructor(owner, name) { super(); this._nodeType = 2; this._owner = owner; this._name = name; }
    get name() { return this._name; }
    get value() { return this._owner._attrs[this._name]; }
    get textContent() { return this.value; }
    get nodeValue() { return this.value; }
    get ownerElement() { return this._owner; }
  }
  class NamedNodeMap {
    constructor(el) { const names = Object.keys(el._attrs); names.forEach((n, i) => { this[i] = new Attr(el, n); }); this.length = names.length; this._el = el; }
    item(i) { return this[i] || null; }
    getNamedItem(n) { return n in this._el._attrs ? new Attr(this._el, n) : null; }
  }
  NamedNodeMap.prototype[Symbol.iterator] = Array.prototype.values;
  class NodeList {
    constructor(a) { a.forEach((x, i) => { this[i] = x; }); this.length = a.length; }
    item(i) { return this[i] || null; }
  }
  NodeList.prototype[Symbol.iterator] = Array.prototype.values;
  NodeList.prototype.forEach = Array.prototype.forEach;
  NodeList.prototype.values = Array.prototype.values;
  class DocumentFragment extends Node {
    constructor() { super(); this._nodeType = 11; }
  }
  Object.assign(DocumentFragment.prototype, finders);
  class ShadowRoot extends DocumentFragment {
    constructor(host) { super(); this._host = host; }
    get host() { return this._host; }
    get innerHTML() { return this._kids.map((k) => k.outerHTML).join(''); }
  }
  class Document extends Node {
    constructor() { super(); this._nodeType = 9; this._title = 'Shop'; }
    get title() { return this._title; }
    set title(v) { log.mutations++; this._title = v; }
    get body() { return this._kids[0]; }
    get cookie() { return 'sid=secret-cookie'; }
    get implementation() { return { createHTMLDocument: () => new Document() }; }
    createElement(t) { return new HTMLElement(t); }
    importNode(n, deep) { return n._copy(deep); }
  }
  Object.assign(Document.prototype, finders);
  return { EventTarget, Node, CharacterData, Text, Element, HTMLElement, HTMLInputElement, HTMLTextAreaElement, HTMLIFrameElement, HTMLTemplateElement, Attr, NamedNodeMap, NodeList, DocumentFragment, ShadowRoot, Document };
};
const { EventTarget, Node, CharacterData, Text, Element, HTMLElement, HTMLInputElement, HTMLTextAreaElement, HTMLIFrameElement, HTMLTemplateElement, Attr, NamedNodeMap, NodeList, DocumentFragment, ShadowRoot, Document } = makeDom();
// 页面自己定义的自定义元素:构造时跑页面代码
class XCard extends HTMLElement { constructor(...a) { super(...a); log.pageCode++; } }

const doc = new Document();
const body = new HTMLElement('body');
doc._kids.push(body);
const add = (parent, el) => { parent._kids.push(el); el.parentNode = parent; return el; };
const list = add(body, new HTMLElement('ul'));
add(list, new HTMLElement('li', { 'data-id': '1' }, 'apple'));
add(list, new HTMLElement('li', { 'data-id': '2' }, 'pear'));
const form = add(body, new HTMLElement('form'));
const pw = add(form, new HTMLInputElement('input', { type: 'password', value: 'hunter2' }));
add(form, new HTMLInputElement('input', { type: 'hidden', name: 'csrf', value: 'tok-123' }));
add(form, new HTMLInputElement('input', { type: 'text', name: 'q', value: 'shoes' }));
add(form, new HTMLInputElement('input', { type: 'number', name: 'otp', autocomplete: 'one-time-code', value: '482913' }));
add(add(form, new HTMLTextAreaElement('textarea', { name: 'code', autocomplete: 'one-time-code' })), new Text('otp-in-textarea'));
add(add(form, new HTMLTextAreaElement('textarea', { name: 'note' })), new Text('leave at door'));
add(form, new XCard('x-card', {}, 'card'));
// template 里的隐藏字段:不在文档树上,innerHTML 照样写出来
add(add(form, new HTMLTemplateElement('template'))._content, new HTMLInputElement('input', { type: 'hidden', name: 'tpl', value: 'tpl-secret' }));
// 同源 iframe:里面的文档用另一套接口;密码框里是用户打的字(值,不是属性)
const frame = makeDom();
const frameDoc = new frame.Document();
const frameBody = new frame.HTMLElement('body');
frameDoc._kids.push(frameBody);
const frameForm = add(frameBody, new frame.HTMLElement('form'));
add(frameForm, new frame.HTMLInputElement('input', { type: 'password', name: 'password' }))._value = 'typed-in-frame';
add(frameForm, new frame.HTMLInputElement('input', { type: 'hidden', name: 'csrf', value: 'tok-frame' }));
add(frameForm, new frame.HTMLInputElement('input', { type: 'text', name: 'q', value: 'frame-q' }));
add(body, new HTMLIFrameElement('iframe'))._doc = frameDoc;
// 开放的 shadow root:里面的节点不在文档树上
const host = add(body, new HTMLElement('div'));
host._shadow = new ShadowRoot(host);
add(host._shadow, new HTMLInputElement('input', { type: 'password', value: 'shadow-default' }));
add(host._shadow, new HTMLInputElement('input', { type: 'hidden', value: 'shadow-token' }));
// SVG 里也有叫 template 的元素:它没有 content,子节点就在文档树上
add(add(add(body, new Element('svg')), new Element('template')), new Element('circle'));

// 用例的 page:跑之前页面自己做的事(浏览器里是页面脚本),返回跑完后怎么还原
const pageActions = {
  // 「显示密码」:读取世界建起之后把密码框改成 text
  showPassword: () => { pw._attrs.type = 'text'; return () => { pw._attrs.type = 'password'; }; },
  // 页面异步重绘/清空输入框:脚本已经拿到的旧 Text 节点仍然存在,但不再有 parentNode。
  detachSecretText: () => {
    const area = form.querySelector('textarea'), old = area._kids.slice();
    const timer = setTimeout(() => { area._kids = []; for (const n of old) n.parentNode = null; }, 0);
    return () => { clearTimeout(timer); area._kids = old; for (const n of old) n.parentNode = area; };
  },
};
// 页面上的节点此刻的样子(属性、值、文字,含 template 的内容、shadow root、iframe 里的文档):读完和读之前一比,就知道原件有没有被改过
const pageState = () => {
  const out = [];
  const walk = (n) => {
    out.push([n.tagName || n._nodeType, n._attrs, n._value, n._text]);
    for (const k of n._kids) walk(k);
    for (const x of [n._content, n._shadow, n._doc]) if (x) walk(x);
  };
  walk(doc);
  return stringify(out);
};

class Location {
  constructor() { this.href = 'https://shop.test/list'; this.origin = 'https://shop.test'; }
  assign() { log.navigations++; }
  replace() { log.navigations++; }
}
const location = new Location();
Object.defineProperty(location, 'toString', { value: function () { return this.href; } });
Object.defineProperty(location, 'valueOf', { value: function () { return this; } });

// 会发请求的页面接口:真的被调到就记一笔
class XMLHttpRequest { constructor() { log.requests.push({ via: 'xhr' }); } open(m, u) { log.requests.push({ via: 'xhr', method: m, url: u }); } send() {} }
class WebSocket { constructor(u) { log.requests.push({ via: 'ws', url: u }); } }
class EventSource { constructor(u) { log.requests.push({ via: 'sse', url: u }); } }
class Image { constructor() { log.requests.push({ via: 'img' }); } }
const navigator = { userAgent: 'test', sendBeacon(u) { log.requests.push({ via: 'beacon', url: u }); return true; } };

Object.assign(globalThis, {
  XMLHttpRequest, WebSocket, EventSource, Image,
  Node, CharacterData, Text, Element, HTMLElement, HTMLInputElement, HTMLTextAreaElement, HTMLIFrameElement, HTMLTemplateElement, Attr, NamedNodeMap, NodeList, DocumentFragment, ShadowRoot, Document, EventTarget,
  document: doc, location,
  getComputedStyle() { return { display: 'block' }; },
  open() { log.navigations++; },
  fetch: async (url, opts) => {
    log.requests.push({ url, method: opts && opts.method, credentials: opts && opts.credentials, redirect: opts && opts.redirect });
    // 不跟随跳转时浏览器给的是 opaqueredirect 响应
    if (url.endsWith('/moved')) return { type: 'opaqueredirect', status: 0, ok: false, url: '', body: null, headers: new Headers() };
    return new Response(stringify({ items: [1, 2], token: 'abc' }), { headers: { 'content-type': 'application/json' } });
  },
});

// Node 自带的 navigator 只有 getter:换成假的
Object.defineProperty(globalThis, 'navigator', { value: navigator, configurable: true, writable: true });

(0, eval)(prelude);
const run = globalThis.__mikaReadRun;

(async () => {
  const out = {};
  for (const c of cases) {
    const before = { mutations: log.mutations, clicks: log.clicks, navigations: log.navigations, pageCode: log.pageCode, requests: log.requests.length };
    const lim = { resultChars: c.resultChars || 5000, sleepMs: 50 };
    const undo = c.page ? pageActions[c.page]() : null;
    const state = pageState();
    let r;
    try { r = await run(c.code, c.args == null ? null : c.args, lim); } catch (e) { r = { ok: false, error: 'harness', message: String(e) }; }
    const pageChanged = pageState() !== state;
    if (undo) undo();
    out[c.name] = Object.assign({}, r, { pageChanged,
      mutations: log.mutations - before.mutations, clicks: log.clicks - before.clicks,
      navigations: log.navigations - before.navigations, pageCode: log.pageCode - before.pageCode, requests: log.requests.slice(before.requests),
    });
  }
  write(stringify(out));
})();
