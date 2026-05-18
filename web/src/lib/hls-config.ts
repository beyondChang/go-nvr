/**
 * Shared hls.js configuration optimized for Raspberry Pi 3B.
 *
 * Low buffer sizes minimize memory usage on constrained devices.
 * enableWorker:false ensures compatibility with RPi browsers.
 *
 * liveSyncDuration forces the player to stay close to the live edge (~5s),
 * reducing the inherent HLS segment delay caused by 8s GOP cameras.
 */

import { getCredentials } from '$lib/api';
import type Hls from 'hls.js';

/** RPi-optimized hls.js configuration. */
export function createHlsConfig(): Partial<Hls.Config> {
  return {
    enableWorker: false,
    maxBufferLength: 5,
    maxMaxBufferLength: 10,
    maxBufferSize: 10 * 1024 * 1024, // 10 MB
    backBufferLength: 2,
    liveSyncDuration: 5,              // 目标延迟 5s (段时长 ~8s, 实际 ~8-10s)
    liveMaxLatencyDuration: 12,       // 最大延迟 12s，防止漂远
    xhrSetup: (xhr: XMLHttpRequest, url: string) => {
      const creds = getCredentials();
      if (creds) {
        if (!xhr.readyState) {
          xhr.open('GET', url, true);
        }
        xhr.setRequestHeader('Authorization', 'Basic ' + btoa(`${creds.username}:${creds.password}`));
      }
    },
  };
}
