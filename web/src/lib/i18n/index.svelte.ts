/**
 * i18n module — 固定返回中文，移除国际化切换功能
 */

import zh from './zh.json';

type Translations = Record<string, string>;

const dict: Translations = zh;

export function t(key: string, params?: Record<string, string | number>): string {
  let value = dict[key];

  if (value === undefined) {
    return key;
  }

  if (params) {
    for (const [k, v] of Object.entries(params)) {
      value = value.replace(`{${k}}`, String(v));
    }
  }

  return value;
}

// 保留兼容性导出（空函数）
export function initI18n(): void { /* no-op */ }
