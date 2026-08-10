#!/usr/bin/env node
import readline from 'node:readline';
import { COMMANDS, helpText, menuText, runLine } from './commands.js';
import { pickFolder } from './pick.js';

async function main() {
  const argv = process.argv.slice(2);
  if (argv.length === 0) {
    // Packaged CLI：无参数时打印用法；源码/开发时仍进交互菜单
    if (process.pkg) {
      console.log(helpText());
      console.log('');
      console.log('提示：日常请用窗口版 BooxinStudio.exe');
      return;
    }
    await menuRepl();
    return;
  }
  if (argv[0] === '-h' || argv[0] === '--help') {
    console.log(helpText());
    return;
  }
  try {
    const cmd = argv[0];
    const args = argv.slice(1);
    const def = COMMANDS[cmd];
    if (!def) throw new Error(`未知命令: ${cmd}（输入 help 或直接双击看菜单）`);
    const out = def.run(args) ?? '';
    if (out) console.log(out);
  } catch (e) {
    console.error(`错误: ${e.message || e}`);
    process.exitCode = 1;
  }
}

async function menuRepl() {
  console.log(menuText());
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
    prompt: '请选编号> ',
  });
  rl.prompt();
  rl.on('line', (line) => {
    const t = line.trim();
    if (!t) {
      rl.prompt();
      return;
    }
    if (t === '0' || t === 'exit' || t === 'quit' || t === 'q') {
      rl.close();
      return;
    }
    try {
      let out = '';
      switch (t) {
        case '1':
          out = runLine('new greeting');
          break;
        case '2':
          out = runLine('new font');
          break;
        case '3':
          out = runLine('new icon');
          break;
        case '4':
          out = runLine('new theme');
          break;
        case '5':
          out = runLine('pack');
          break;
        case '6': {
          const dir = pickFolder('选择要校验的插件文件夹');
          if (!dir) throw new Error('已取消');
          out = COMMANDS.validate.run([dir]);
          break;
        }
        case '7':
          out = runLine('doctor');
          break;
        case '8':
        case 'help':
          out = helpText();
          break;
        default:
          out = runLine(t);
      }
      if (out) console.log(out);
    } catch (e) {
      console.error(`错误: ${e.message || e}`);
    }
    console.log('');
    console.log(menuText());
    rl.prompt();
  });
  await new Promise((resolve) => rl.on('close', resolve));
  console.log('已退出');
}

main();
