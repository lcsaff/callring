const fs = require('fs');
const vm = require('vm');
const path = require('path');
const { spawn } = require('child_process');
const assert = require('assert/strict');
const session = path.resolve(__dirname, '..');
const html = fs.readFileSync(path.join(session, 'content/contract-v1-aligned.html'), 'utf8');
for (const match of html.matchAll(/<script>([\s\S]*?)<\/script>/g)) new vm.Script(match[1]);
const browser = spawn('C:/Program Files/Google/Chrome/Application/chrome.exe', [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--remote-debugging-port=65102', '--remote-debugging-address=127.0.0.1',
  '--user-data-dir=' + path.join(__dirname, 'preview-browser'), 'about:blank'
], { windowsHide: true, stdio: 'ignore' });
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
let socket;
(async () => {
  let target;
  for (let i = 0; i < 40; i++) {
    try {
      const targets = await (await fetch('http://127.0.0.1:65102/json/list')).json();
      target = targets.find(item => item.type === 'page');
      if (target) break;
    } catch {}
    await pause(250);
  }
  assert(target, 'Headless browser started');
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  let next = 0;
  const pending = new Map(), errors = [];
  socket.onmessage = event => {
    const value = JSON.parse(event.data);
    if (value.method === 'Runtime.exceptionThrown') errors.push(value.params.exceptionDetails);
    if (value.id && pending.has(value.id)) {
      const { resolve, reject } = pending.get(value.id);
      pending.delete(value.id);
      value.error ? reject(new Error(JSON.stringify(value.error))) : resolve(value.result);
    }
  };
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++next;
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
  const evaluate = async expression => {
    const value = await send('Runtime.evaluate', { expression, returnByValue: true });
    if (value.exceptionDetails) throw new Error(JSON.stringify(value.exceptionDetails));
    return value.result.value;
  };
  await send('Runtime.enable');
  await send('Page.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 1120, deviceScaleFactor: 1, mobile: false });
  await send('Page.navigate', { url: 'http://localhost:65101' });
  for (let i = 0; i < 40; i++) {
    if (await evaluate('document.readyState === "complete" && typeof customThemeReady !== "undefined" && customThemeReady')) break;
    await pause(100);
  }
  assert.equal(await evaluate('document.querySelectorAll(".phone").length'), 2);
  assert.equal(await evaluate('document.querySelector(".screen").textContent.includes("今日已记录来电")'), false, 'Home does not invent v1 aggregates');
  await evaluate('switchPage("callers")');
  assert.equal(await evaluate('document.querySelectorAll(".screen .filters").length'), 0, 'Caller filters stay within v1 contract');
  await evaluate('window.brainstorm = null; localStorage.removeItem(themeKey); theme="red"; render()');
  assert.equal(await evaluate('document.querySelectorAll(".phone[data-theme=red]").length'), 2, 'Red is the initial default');
  await evaluate('switchPage("settings")');
  for (const choice of ['sky', 'green', 'red']) {
    await evaluate(`document.querySelector('.phone .theme-btn[data-theme=${choice}]').click()`);
    assert.equal(await evaluate(`document.querySelectorAll('.phone[data-theme=${choice}]').length`), 2, 'Theme updates every preview');
    assert.equal(await evaluate(`localStorage.getItem(themeKey)`), choice, 'Theme preference is persisted');
  }
  await evaluate('setTheme("green")');
  await send('Page.reload');
  for (let i = 0; i < 40; i++) {
    if (await evaluate('document.readyState === "complete" && typeof customThemeReady !== "undefined" && customThemeReady && theme === "green"')) break;
    await pause(100);
  }
  assert.equal(await evaluate('document.querySelectorAll(".phone[data-theme=green]").length'), 2, 'Theme survives reload');
  await evaluate('window.brainstorm=null; setTheme("red"); switchPage("home")');
  assert.deepEqual(await evaluate('[100,80,79,60,59,0,null].map(scoreTone)'), ['good','good','medium','medium','bad','bad','none'], 'Score band boundaries and unknown scores');
  const scoreColors = [];
  for (const choice of ['red', 'sky', 'green']) {
    await evaluate(`setTheme('${choice}'); switchPage('callers')`);
    assert.equal(await evaluate('document.querySelectorAll(".score-good").length'), 2);
    assert.equal(await evaluate('document.querySelectorAll(".score-medium").length'), 2);
    assert.equal(await evaluate('document.querySelectorAll(".score-bad").length'), 2);
    scoreColors.push(await evaluate('Array.from(document.querySelectorAll(".phone:first-child .metric.score")).map(el=>getComputedStyle(el).color)'));
  }
  assert.deepEqual(scoreColors[0], scoreColors[1], 'Score colors independent of blue theme');
  assert.deepEqual(scoreColors[0], scoreColors[2], 'Score colors independent of green theme');
  await evaluate('registerTheme({id:"test-extension",label:"扩展验证",light:{...themes.red.light,primary:"#663399"},dark:{...themes.red.dark,primary:"#DAB8FF"}}); render(); setTheme("test-extension")');
  assert.equal(await evaluate('document.querySelectorAll(".phone[data-theme=test-extension]").length'), 2, 'New theme needs configuration only');
  assert.equal(await evaluate('document.querySelectorAll(".phone .theme-btn[data-theme=test-extension]").length'), 0, 'Caller page does not need theme-specific code');
  await evaluate('switchPage("settings")');
  assert.equal(await evaluate('document.querySelectorAll(".phone .theme-btn[data-theme=test-extension]").length'), 2, 'Settings discovers new theme automatically');
  await evaluate('delete themes["test-extension"]; setTheme("red"); switchPage("home")');

  const baselineTheme = await evaluate('theme');
  assert.equal(await evaluate('previewColor("#12345")'), false, 'Invalid hex rejected');
  assert.equal(await evaluate('theme'), baselineTheme, 'Invalid input leaves theme unchanged');
  const contrastExpression = `Array.from(document.querySelectorAll('.phone')).map(el=>{
    const style=getComputedStyle(el);
    const lum=hex=>{const c=hex.trim().replace('#','').match(/../g).map(v=>parseInt(v,16)/255).map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4);return c[0]*.2126+c[1]*.7152+c[2]*.0722};
    const ratio=(a,b)=>{const x=lum(style.getPropertyValue(a)),y=lum(style.getPropertyValue(b));return (Math.max(x,y)+.05)/(Math.min(x,y)+.05)};
    return [ratio('--primary','--on-primary'),ratio('--surface','--ink')];
  })`;
  for (const color of ['#FFFFFF','#000000','#FFFF00','#00FF00','#663399']) {
    assert.equal(await evaluate(`previewColor('${color}')`), true);
    const pairs = await evaluate(contrastExpression);
    assert(pairs.every(pair=>pair.every(value=>value>=4.5)), 'Generated color contrast: '+color);
  }
  assert.equal(await evaluate('applyCustomColor("#663399")'), true);
  assert.equal(await evaluate('localStorage.getItem(customColorKey)'), '#663399');
  await send('Page.reload');
  for(let i=0;i<40;i++){
    if(await evaluate('document.readyState==="complete" && typeof customThemeReady!=="undefined" && customThemeReady && theme==="custom"'))break;
    await pause(100);
  }
  assert.equal(await evaluate('theme'), 'custom', 'Custom theme restored');
  assert.equal(await evaluate('themes.custom.seed'), '#663399', 'Custom seed restored');
  assert.equal(await evaluate('document.querySelectorAll("#theme-controls .theme-btn").length'), 3, 'Three presets remain');
  await evaluate('window.brainstorm=null; setTheme("sky")');
  assert.equal(await evaluate('document.getElementById("custom-color-status").textContent.includes("天蓝色预设")'), true, 'Preset status reflects active selection');
  assert.equal(await evaluate('localStorage.getItem(customColorKey)'), '#663399', 'Preset retains custom color');
  await evaluate('applyCustomColor(); switchPage("callers")');
  assert.deepEqual(await evaluate('Array.from(document.querySelectorAll(".phone:first-child .metric.score")).map(el=>getComputedStyle(el).color)'),scoreColors[0],'Custom theme retains score semantics');
  await evaluate('setTheme("red"); switchPage("home")');

  for (const page of ['home', 'callers', 'report', 'settings', 'edit', 'surfaces']) {
    assert.equal(await evaluate(`switchPage('${page}'); document.querySelectorAll('.screen').length`), 2);
    assert.equal(await evaluate('Array.from(document.querySelectorAll(".screen")).every(el => el.textContent.trim().length > 30)'), true);
  }
  assert.equal(await evaluate('document.querySelectorAll(".phone.dark").length'), 1, 'Light/dark comparison');
  assert.equal(await evaluate('document.getElementById("font")'), null, 'No large-font preview');
  await evaluate('switchPage("edit"); document.querySelector(".screen .action-button").click()');
  assert.equal(await evaluate('document.querySelectorAll(".mock-toast").length'), 1);
  await evaluate('switchPage("home")');
  assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'), true);
  await pause(150);
  const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
  fs.writeFileSync(path.join(__dirname, 'visual-directions-desktop.png'), Buffer.from(shot.data, 'base64'));
  await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await pause(150);
  assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'), true, 'No mobile page overflow');
  const mobileShot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
  fs.writeFileSync(path.join(__dirname, 'visual-directions-mobile.png'), Buffer.from(mobileShot.data, 'base64'));
  assert.equal(errors.length, 0, 'No JavaScript runtime exceptions');
  console.log('PASS: custom color picker and hex input; generated light/dark contrast; invalid input rejected; custom color persisted; three presets; no large-font preview; score semantics unchanged; 6 screens and desktop/mobile width; no runtime exceptions.');
  await send('Browser.close');
})().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => {
  if (socket) socket.close();
  browser.kill();
});
