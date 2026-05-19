/**
 * Format utility functions — 固定中文格式
 */

export function formatDate(dateStr: string): string {
  const date = new Date(dateStr);
  return date.toLocaleString('zh-CN', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * Format a duration in seconds to a human-readable string.
 * e.g. "1时 30分 15秒"
 */
export function formatDuration(seconds: number): string {
  const hrs = Math.floor(seconds / 3600);
  const mins = Math.floor((seconds % 3600) / 60);
  const secs = Math.floor(seconds % 60);
  
  if (hrs > 0) {
    return `${hrs}时 ${mins}分 ${secs}秒`;
  }
  if (mins > 0) {
    return `${mins}分 ${secs}秒`;
  }
  return `${secs}秒`;
}

/**
 * Format bytes to a human-readable file size string.
 * e.g. "1.50 GB"
 */
export function formatFileSize(bytes: number): string {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let size = bytes;
  let unitIndex = 0;
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024;
    unitIndex++;
  }
  return `${size.toFixed(2)} ${units[unitIndex]}`;
}

/**
 * Determine the best unit for chart axis display based on data range.
 * Returns scaled values and unit label for Chart.js ticks callback.
 */
export function formatChartValue(bytes: number): { value: number; unit: string; label: string } {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex++;
  }
  return {
    value: Math.round(value * 10) / 10,  // 1 decimal place
    unit: units[unitIndex],
    label: units[unitIndex],
  };
}

/**
 * Determine the best unit for a set of byte values (for chart axis).
 * Returns the divisor and unit label so all values use the same scale.
 */
export function getChartUnit(bytesArray: number[]): { divisor: number; unit: string } {
  const maxBytes = Math.max(...bytesArray, 0);
  const units = [
    { threshold: 1024, divisor: 1, unit: 'B' },
    { threshold: 1024 * 1024, divisor: 1024, unit: 'KB' },
    { threshold: 1024 * 1024 * 1024, divisor: 1024 * 1024, unit: 'MB' },
    { threshold: 1024 * 1024 * 1024 * 1024, divisor: 1024 * 1024 * 1024, unit: 'GB' },
  ];
  
  // Default to TB for very large values
  if (maxBytes >= 1024 * 1024 * 1024 * 1024) {
    return { divisor: 1024 * 1024 * 1024 * 1024, unit: 'TB' };
  }
  
  // Find the largest unit whose threshold is <= maxBytes
  for (let i = units.length - 1; i >= 0; i--) {
    if (maxBytes >= units[i].threshold) {
      return { divisor: units[i].divisor, unit: units[i].unit };
    }
  }
  return { divisor: 1, unit: 'B' };
}
