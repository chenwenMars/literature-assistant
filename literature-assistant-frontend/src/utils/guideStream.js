// 思考事件使用JSON保留原始换行和空白；协议错误必须终止本次生成。
export function decodeReasoning(data) {
  const message = JSON.parse(data)
  if (message === null || typeof message.content !== 'string') {
    throw new Error('AI思考事件格式错误：content必须为字符串')
  }
  return message.content
}
