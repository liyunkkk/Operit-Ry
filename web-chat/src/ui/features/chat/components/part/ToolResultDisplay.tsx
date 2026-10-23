import { useMemo, useState } from 'react';
import { FileDiffDisplay, type FileDiff } from './FileDiffDisplay';
import { ToolResultDetailDialog } from './DialogComponents';
import { ToolResultRow } from './XmlCanvasSummaryComponents';
import type { WebMessageContentBlock } from '../../util/chatTypes';

function extractTaggedContent(content: string, tagName: string) {
  const match = content.match(new RegExp(`<${tagName}[^>]*>([\\s\\S]*?)<\\/${tagName}>`, 'i'));
  return match?.[1]?.trim() ?? '';
}

function stripFileDiff(result: string) {
  return result.replace(/<file-diff[\s\S]*<\/file-diff>/gi, '').trim();
}

function decodeXmlText(input: string) {
  return input
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'");
}

function extractFileDiff(result: string): FileDiff | null {
  const fileDiffMatch = result.match(/<file-diff\b([^>]*)>([\s\S]*?)<\/file-diff>/i);
  if (!fileDiffMatch) {
    return null;
  }

  const attributes = fileDiffMatch[1] ?? '';
  const body = fileDiffMatch[2] ?? '';
  const path = attributes.match(/\bpath="([^"]*)"/i)?.[1] ?? '';
  const details = attributes.match(/\bdetails="([^"]*)"/i)?.[1] ?? '';
  const cdataContent = body.match(/<!\[CDATA\[([\s\S]*?)\]\]>/i)?.[1];
  const diffContent = decodeXmlText((cdataContent ?? body).trim());

  return {
    path,
    details,
    diffContent
  };
}

function copyText(text: string) {
  void navigator.clipboard.writeText(text).catch(() => {});
}

function isFileDiffTool(toolName: string) {
  return toolName === 'apply_file' || toolName === 'create_file' || toolName === 'edit_file';
}

function normalizeToolResult(block: WebMessageContentBlock) {
  const toolName = block.attrs?.name?.trim() || '未知工具';
  const status = (block.attrs?.status?.trim()?.toLowerCase() || 'success');
  const outerContent = block.content ?? '';
  const rawResultContent = extractTaggedContent(outerContent, 'content') || outerContent.trim();
  const isSuccess = status === 'success';
  const fileDiff =
    isFileDiffTool(toolName) && isSuccess && rawResultContent.includes('<file-diff')
      ? extractFileDiff(rawResultContent)
      : null;
  const rawErrorContent = isSuccess
    ? ''
    : extractTaggedContent(rawResultContent, 'error') || rawResultContent;
  const resultContent = isSuccess
    ? stripFileDiff(rawResultContent)
    : permissionDenialLabel(rawErrorContent) ?? rawErrorContent;

  return {
    toolName,
    isSuccess,
    resultContent,
    fileDiff
  };
}

/**
 * 权限拒绝的正文是写给模型的英文指令（含重试规则），直接贴出来会在对话里出现半句英文。
 * 这里按同样的前缀换成结论；前缀必须与 app 侧 ToolPermissionSystem.kt 的常量保持一致。
 * 子代理被中止时正文是「<task_error>Turn <id> failed: <指令></task_error>」，所以先剥掉这层包装。
 */
function permissionDenialLabel(resultContent: string): string | null {
  const trimmed = stripTurnFailureWrapper(unwrapTaskError(resultContent)).trim();
  if (trimmed.startsWith('Automatic permission review failed')) {
    return '自动审核未完成，该操作未执行';
  }
  // 取消前缀必须先判：它描述的是「本轮被中止、该操作没跑」，说成「已拒绝」会误导用户。
  if (trimmed.startsWith('Tool execution cancelled because automatic permission review')) {
    return '自动审核中止了本轮，该操作未执行';
  }
  if (trimmed.startsWith('Automatic permission review denied')) {
    return '自动审核已拒绝该操作';
  }
  if (trimmed.startsWith('Tool execution denied by user.')) {
    return '你已拒绝该操作';
  }
  if (trimmed.startsWith('Tool execution denied by permission settings.')) {
    return '权限设置禁止该操作';
  }
  return null;
}

/** 取出 <task_error> 的正文，没有该标签时原样返回。 */
function unwrapTaskError(content: string): string {
  const match = content.match(/<task_error>([\s\S]*?)<\/task_error>/i);
  return match ? decodeXmlText(match[1] ?? '') : content;
}

/** 去掉「Turn <id> failed: 」这层包装，它是内部记账用的。 */
function stripTurnFailureWrapper(content: string): string {
  return content.replace(/^Turn\s+\S+\s+failed:\s*/, '');
}

function buildSummaryText(result: string, isSuccess: boolean) {
  if (result.trim()) {
    return result.slice(0, 200);
  }
  return isSuccess ? '执行成功' : '执行失败';
}

function buildSemanticDescription(toolName: string, summaryText: string, result: string, isSuccess: boolean) {
  const normalizedPreview = result
    .replace(/\n/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  const preview =
    normalizedPreview.length <= 20 ? normalizedPreview : `${normalizedPreview.slice(0, 20)}...`;
  const statusLabel = isSuccess ? '成功' : '失败';
  return preview
    ? `工具执行结果: ${toolName}，${statusLabel}，${preview}`
    : `工具执行结果: ${toolName}，${statusLabel}，${summaryText}`;
}

export function ToolResultDisplay({ block }: { block: WebMessageContentBlock }) {
  const { toolName, isSuccess, resultContent, fileDiff } = useMemo(() => {
    return normalizeToolResult(block);
  }, [block]);
  const [detailOpen, setDetailOpen] = useState(false);
  const hasContent = resultContent.trim().length > 0;
  const summaryText = buildSummaryText(resultContent, isSuccess);
  const semanticDescription = buildSemanticDescription(
    toolName,
    summaryText,
    resultContent,
    isSuccess
  );

  return (
    <>
      {fileDiff ? (
        <FileDiffDisplay diff={fileDiff} />
      ) : (
        <ToolResultRow
          isSuccess={isSuccess}
          onClick={hasContent ? () => setDetailOpen(true) : null}
          onCopyClick={hasContent ? () => copyText(resultContent) : null}
          semanticDescription={semanticDescription}
          summary={summaryText}
        />
      )}

      {detailOpen && !fileDiff ? (
        <ToolResultDetailDialog
          isSuccess={isSuccess}
          onDismiss={() => setDetailOpen(false)}
          result={resultContent}
          toolName={toolName}
        />
      ) : null}
    </>
  );
}
