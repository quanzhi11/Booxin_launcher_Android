/**
 * Plugin-comment report review, response shape mirrors desktop
 * DirectMessageReportResult (booxin-launcher2.3.0).
 */

const SEVERE = [
  '自杀',
  '枪支',
  '毒品',
  '未成年色情',
  '儿童色情',
  '强奸',
];
const ABUSE = [
  '傻逼',
  '死全家',
  '操你',
  '尼玛',
  '滚粗',
  '废物',
  '脑残',
  '白痴',
  '辱骂',
  '垃圾人',
];
const SPAM = ['加微信', '加qq', '代充', '刷钻', '免费领取', 'http://', 'https://'];

function levelName(level) {
  switch (level) {
    case 1:
      return '警告';
    case 2:
      return '短期限制';
    case 3:
      return '长期限制';
    case 4:
      return '人工复核';
    default:
      return '无处罚';
  }
}

function buildMessage(result) {
  if (result.message) return result.message;
  const lines = ['【审核结果】'];
  if (!result.aiReviewed) {
    lines.push('状态：已记录举报，等待人工复核。');
  } else if (result.reportValid === true) {
    lines.push('举报是否成立：是');
    lines.push(`违规等级：${result.actionLevelName}`);
    if (result.actionLevel === 1) {
      lines.push('说明：评论已被标记；严重时可隐藏。');
    } else if (result.actionLevel >= 2 && result.actionLevel <= 3) {
      lines.push('说明：违规评论已自动隐藏。');
    } else if (result.actionLevel === 4) {
      lines.push('说明：四级案件需管理员人工复核。');
    }
  } else if (result.reportValid === false) {
    lines.push('举报是否成立：否');
  } else {
    lines.push('举报是否成立：待确认');
  }
  if (result.category && !['none', 'manual', 'pending'].includes(result.category)) {
    lines.push(`违规类型：${result.category}`);
  }
  if (result.aiSummary) lines.push(`摘要：${result.aiSummary}`);
  if (result.actionTaken) lines.push(`处理结果：${result.actionTaken}`);
  return lines.join('\n');
}

function heuristicReview(text, reason) {
  const hay = `${text}\n${reason || ''}`.toLowerCase();
  if (SEVERE.some((k) => hay.includes(k.toLowerCase()))) {
    return {
      aiReviewed: true,
      reportValid: true,
      category: 'severe',
      actionLevel: 3,
      actionLevelName: levelName(3),
      aiSummary: '检测到严重违规表述。',
      actionTaken: '已隐藏评论',
      message: '',
    };
  }
  if (ABUSE.some((k) => hay.includes(k.toLowerCase()))) {
    return {
      aiReviewed: true,
      reportValid: true,
      category: 'harassment',
      actionLevel: 2,
      actionLevelName: levelName(2),
      aiSummary: '检测到辱骂/骚扰内容。',
      actionTaken: '已隐藏评论',
      message: '',
    };
  }
  if (SPAM.some((k) => hay.includes(k.toLowerCase()))) {
    return {
      aiReviewed: true,
      reportValid: true,
      category: 'spam',
      actionLevel: 1,
      actionLevelName: levelName(1),
      aiSummary: '疑似广告/引流。',
      actionTaken: '已记录警告',
      message: '',
    };
  }
  return {
    aiReviewed: true,
    reportValid: false,
    category: 'none',
    actionLevel: 0,
    actionLevelName: levelName(0),
    aiSummary: '未发现明显违规。',
    actionTaken: '',
    message: '',
  };
}

/**
 * @returns {Promise<{
 *   aiReviewed: boolean,
 *   reportValid: boolean|null,
 *   category: string,
 *   actionLevel: number,
 *   actionLevelName: string,
 *   aiSummary: string,
 *   actionTaken: string,
 *   message: string,
 *   hideComment: boolean,
 * }>}
 */
export async function reviewPluginCommentReport({ body, reason }) {
  const apiKey = String(
    process.env.CHAT_MODERATION_API_KEY || process.env.OPENAI_API_KEY || '',
  ).trim();
  const apiBase = String(
    process.env.CHAT_MODERATION_API_BASE || 'https://api.openai.com/v1',
  ).replace(/\/$/, '');
  const model = String(process.env.CHAT_MODERATION_MODEL || 'gpt-4o-mini').trim();

  let result;
  if (!apiKey) {
    // No external key: still run local heuristics (same spirit as desktop AI pipeline).
    result = heuristicReview(String(body || ''), String(reason || ''));
  } else {
    try {
      const prompt = [
        '你是内容审核助手。判断插件商店评论是否违规。',
        '只返回 JSON：{"reportValid":boolean,"category":"none|harassment|spam|severe|other","actionLevel":0|1|2|3|4,"summary":"简短中文"}',
        'actionLevel: 0无处罚 1警告 2短期限制(隐藏) 3长期限制(隐藏) 4仅人工复核。',
        `评论：${String(body || '').slice(0, 800)}`,
        `举报原因：${String(reason || '未填写').slice(0, 200)}`,
      ].join('\n');
      const resp = await fetch(`${apiBase}/chat/completions`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${apiKey}`,
          'Content-Type': 'application/json',
          Accept: 'application/json',
        },
        body: JSON.stringify({
          model,
          temperature: 0,
          response_format: { type: 'json_object' },
          messages: [
            { role: 'system', content: '只输出 JSON。' },
            { role: 'user', content: prompt },
          ],
        }),
      });
      if (!resp.ok) throw new Error(`moderation http ${resp.status}`);
      const payload = await resp.json();
      const raw = payload?.choices?.[0]?.message?.content || '{}';
      const parsed = JSON.parse(raw);
      const level = Number(parsed.actionLevel || 0);
      result = {
        aiReviewed: true,
        reportValid: !!parsed.reportValid,
        category: String(parsed.category || 'other'),
        actionLevel: level,
        actionLevelName: levelName(level),
        aiSummary: String(parsed.summary || ''),
        actionTaken:
          parsed.reportValid && level >= 2 && level <= 3
            ? '已隐藏评论'
            : parsed.reportValid && level === 1
              ? '已记录警告'
              : '',
        message: '',
      };
    } catch (err) {
      console.warn('comment moderation AI failed, fallback heuristic:', err?.message || err);
      result = heuristicReview(String(body || ''), String(reason || ''));
    }
  }

  const hideComment =
    result.reportValid === true && result.actionLevel >= 2 && result.actionLevel <= 3;
  if (hideComment && !result.actionTaken) result.actionTaken = '已隐藏评论';
  result.message = buildMessage(result);
  return { ...result, hideComment };
}
