import nodemailer from 'nodemailer';

function smtpConfigured() {
  return !!(process.env.SMTP_HOST && process.env.SMTP_USER && process.env.SMTP_PASS);
}

function createTransport() {
  if (!smtpConfigured()) return null;
  const port = Number(process.env.SMTP_PORT || 465);
  const secure =
    String(process.env.SMTP_SECURE || 'true').toLowerCase() !== 'false' && port !== 587;
  return nodemailer.createTransport({
    host: process.env.SMTP_HOST,
    port,
    secure,
    auth: {
      user: process.env.SMTP_USER,
      pass: process.env.SMTP_PASS,
    },
  });
}

export async function sendRejectEmail({ to, pluginName, reason, username }) {
  if (!to) {
    return { ok: false, skipped: true, message: '申请人未绑定邮箱，跳过发送。' };
  }
  const transport = createTransport();
  if (!transport) {
    return { ok: false, skipped: true, message: '未配置 SMTP，跳过发送。' };
  }
  const fromName = process.env.SMTP_FROM_NAME || 'Booxin 插件市场';
  const from = process.env.SMTP_FROM || process.env.SMTP_USER;
  const subject = `【Booxin】插件申请未通过：${pluginName}`;
  const text = [
    `你好${username ? ` ${username}` : ''}，`,
    '',
    `你提交的插件「${pluginName}」审核未通过。`,
    '',
    '原因：',
    reason || '（未填写）',
    '',
    '可在启动器「插件」栏目修改后重新申请。',
    '',
    '— Booxin 插件市场',
  ].join('\n');

  try {
    await transport.sendMail({
      from: `"${fromName}" <${from}>`,
      to,
      subject,
      text,
    });
    return { ok: true, skipped: false, message: `已发送至 ${to}` };
  } catch (err) {
    console.warn('sendRejectEmail failed:', err);
    return { ok: false, skipped: false, message: err?.message || '发送失败' };
  }
}

export function mailStatus() {
  return { configured: smtpConfigured() };
}
