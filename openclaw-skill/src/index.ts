import { ghosttap_create_task, ghosttap_get_task, ghosttap_health } from "./tools.js";

// 导出工具函数供 OpenClaw 调用
export { ghosttap_create_task, ghosttap_get_task, ghosttap_health };

// 默认导出
export default {
  ghosttap_create_task,
  ghosttap_get_task,
  ghosttap_health,
};