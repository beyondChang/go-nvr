<script lang="ts">
  import { createCamera, updateCamera, getMergeConfig, updateMergeConfig, deleteCameraMergeConfig, getONVIFDeviceDetail } from '$lib/api';
  import type { Camera, CreateCameraRequest, UpdateCameraRequest, DiscoveredDevice, DeviceProfile, MergeConfig } from '$lib/api';
  import { t } from '$lib/i18n';
  import { Eye, EyeOff, AlertCircle, X } from 'lucide-svelte';
  import { showToast } from '$lib/toast';
  import { fade, fly } from 'svelte/transition';

  let { show = false, camera = $bindable(null), oncancel, onsaved } = $props<{
    show: boolean;
    camera?: Camera | null;
    oncancel?: () => void;
    onsaved?: () => void;
  }>();

  let formName = $state('');
  let formProtocol = $state('rtsp');
  let formEncoding = $state('h264');
  let formUrl = $state('');
  let formUsername = $state('');
  let formPassword = $state('');
  let showPassword = $state(false);
  let formEnabled = $state(true);
  let saving = $state(false);
  let formDescription = $state('');
  let formLocation = $state('');
  let formBrand = $state('');
  let formModel = $state('');
  let formSerialNumber = $state('');
  let formRetentionDays = $state(0);
  let formStreamEncoding = $state('');

  // ONVIF probe for selected device
  let detailLoading = $state(false);
  let deviceDetail = $state<{ device_info: any; profiles: DeviceProfile[] } | null>(null);
  let selectedProfileToken = $state('');

  // Merge config (per-camera)
  let mergeConfig = $state<MergeConfig | null>(null);
  let mergeConfigLoading = $state(false);

  let validationErrors = $state<Record<string, string>>({});

  $effect(() => {
    if (formProtocol === 'http') {
      formEncoding = 'jpeg';
    } else if (formProtocol === 'onvif') {
      formEncoding = '';
    } else if (formProtocol === 'rtsp' && (formEncoding === 'jpeg' || formEncoding === '')) {
      formEncoding = 'h264';
    }
  });

  $effect(() => {
    if (camera) {
      formName = camera.name;
      formProtocol = camera.protocol;
      formEncoding = camera.encoding || '';
      if (camera.protocol === 'rtsp_h264') { formProtocol = 'rtsp'; formEncoding = 'h264'; }
      else if (camera.protocol === 'rtsp_h265') { formProtocol = 'rtsp'; formEncoding = 'h265'; }
      else if (camera.protocol === 'rtsp_mjpeg') { formProtocol = 'rtsp'; formEncoding = 'mjpeg'; }
      else if (camera.protocol === 'http_jpeg') { formProtocol = 'http'; formEncoding = 'jpeg'; }
      formUrl = camera.url || '';
      formUsername = camera.username || '';
      formPassword = '';
      showPassword = false;
      formEnabled = camera.enabled;
      formDescription = camera.description || '';
      formLocation = camera.location || '';
      formBrand = camera.brand || '';
      formModel = camera.model || '';
      formSerialNumber = camera.serial_number || '';
      formRetentionDays = camera.retention_days || 0;
      formStreamEncoding = (camera as any).stream_encoding || '';
      validationErrors = {};

      mergeConfig = null;
      mergeConfigLoading = true;
      getMergeConfig(camera.id).then(c => mergeConfig = c).catch(() => mergeConfig = null).finally(() => mergeConfigLoading = false);
    }
  });

  function resetForm() {
    formName = '';
    formProtocol = 'rtsp';
    formEncoding = 'h264';
    formUrl = '';
    formUsername = '';
    formPassword = '';
    showPassword = false;
    formEnabled = true;
    formDescription = '';
    formLocation = '';
    formBrand = '';
    formModel = '';
    formSerialNumber = '';
    formRetentionDays = 0;
    validationErrors = {};
    mergeConfig = null;
    mergeConfigLoading = false;
    deviceDetail = null;
    selectedProfileToken = '';
  }

  function handleCancel() {
    resetForm();
    oncancel?.();
  }

  function validateField(field: string, value: string) {
    if (field === 'name' && !value.trim()) {
      validationErrors['name'] = t('cameras.nameRequired');
    } else if (field === 'url' && !value.trim()) {
      validationErrors['url'] = t('cameras.urlRequired');
    } else {
      delete validationErrors[field];
    }
  }

  function validate(): boolean {
    validationErrors = {};
    if (!formName.trim()) validationErrors['name'] = t('cameras.nameRequired');
    if (!formProtocol) validationErrors['protocol'] = t('cameras.protocolRequired');
    if (!formUrl.trim()) validationErrors['url'] = t('cameras.urlRequired');
    return Object.keys(validationErrors).length === 0;
  }

  async function handleSubmit() {
    if (!validate()) return;
    saving = true;

    try {
      if (camera) {
        const data: UpdateCameraRequest = {
          name: formName,
          protocol: formProtocol,
          url: formUrl,
          enabled: formEnabled,
          description: formDescription || undefined,
          location: formLocation || undefined,
          brand: formBrand || undefined,
          model: formModel || undefined,
          serial_number: formSerialNumber || undefined,
          retention_days: formRetentionDays,
          stream_encoding: formProtocol === 'onvif' ? (formStreamEncoding || undefined) : undefined,
          encoding: formEncoding,
        };
        if (formUsername && formUsername !== camera.username) {
          data.username = formUsername;
        }
        if (formPassword) {
          if (!data.username && formUsername === camera.username) {
            data.username = formUsername;
          }
          data.password = formPassword;
        }
        if (mergeConfig) {
          try {
            await updateMergeConfig(camera.id, mergeConfig);
          } catch { /* ignore */ }
        }
        await updateCamera(camera.id, data);
        showToast(t('cameras.cameraUpdated'), 'success');
      } else {
        const data: CreateCameraRequest = {
          name: formName,
          protocol: formProtocol,
          url: formUrl,
          enabled: formEnabled,
          description: formDescription || undefined,
          location: formLocation || undefined,
          brand: formBrand || undefined,
          model: formModel || undefined,
          serial_number: formSerialNumber || undefined,
          stream_encoding: formProtocol === 'onvif' ? (formStreamEncoding || undefined) : undefined,
          encoding: formEncoding,
        };
        if (formUsername) data.username = formUsername;
        if (formPassword) data.password = formPassword;
        await createCamera(data);
        showToast(t('cameras.cameraAdded'), 'success');
      }
      resetForm();
      onsaved?.();
    } catch (e) {
      showToast(camera ? t('cameras.failedUpdate') : t('cameras.failedAdd'), 'error');
    } finally {
      saving = false;
    }
  }

  async function probeONVIF() {
    if (!formUrl.trim()) return;
    detailLoading = true;
    deviceDetail = null;
    try {
      const url = new URL(formUrl);
      deviceDetail = await getONVIFDeviceDetail(url.hostname);
      if (deviceDetail?.profiles?.length) {
        selectedProfileToken = deviceDetail.profiles[0].token;
      }
    } catch (e) {
      showToast(e instanceof Error ? e.message : t('onvif.failedGetDetails'), 'error');
    } finally {
      detailLoading = false;
    }
  }

  function handleKeydown(e: KeyboardEvent) {
    if (e.key === 'Escape') handleCancel();
  }
</script>

<svelte:window onkeydown={handleKeydown} />

{#if show}
  <div
    class="fixed inset-0 z-50 flex items-start justify-center pt-[10vh]"
    role="dialog"
    aria-modal="true"
  >
    <div class="fixed inset-0 bg-black/50 backdrop-blur-sm" on:click={handleCancel} transition:fade={{ duration: 150 }}></div>
    <div
      class="relative w-full max-w-4xl mx-4 max-h-[80vh] overflow-y-auto card p-6 border th-border th-bg-primary"
      transition:fly={{ y: 20, duration: 200 }}
    >
      <div class="flex items-center justify-between mb-4">
        <h3 class="text-lg font-semibold th-text-primary">
          {camera ? t('cameras.editCamera') : t('cameras.addCamera')}
        </h3>
        <button on:click={handleCancel} class="btn btn-ghost p-1" aria-label={t('cameras.cancel')}>
          <X size={20} />
        </button>
      </div>

      <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
        <!-- Name -->
        <div>
          <label for="cam-name" class="input-label">{t('cameras.name')}</label>
          <input id="cam-name" type="text" class="input {validationErrors['name'] ? 'border-red-500' : ''}" bind:value={formName} on:blur={() => validateField('name', formName)} on:input={() => { if (validationErrors['name']) delete validationErrors['name']; }} />
          {#if validationErrors['name']}
            <p class="th-color-danger text-xs mt-1">{validationErrors['name']}</p>
          {/if}
        </div>

        <!-- Protocol -->
        <div>
          <label for="cam-protocol" class="input-label">{t('cameras.protocol')}</label>
          <select id="cam-protocol" class="input" bind:value={formProtocol}>
            <option value="rtsp">RTSP</option>
            <option value="http">HTTP</option>
            <option value="onvif">ONVIF</option>
          </select>
          {#if validationErrors['protocol']}
            <p class="th-color-danger text-xs mt-1">{validationErrors['protocol']}</p>
          {/if}
        </div>

        <!-- Encoding -->
        <div>
          <label for="cam-encoding" class="input-label">{t('cameras.tableEncoding')}</label>
          <select id="cam-encoding" class="input" bind:value={formEncoding}>
            {#if formProtocol === 'rtsp'}
              <option value="h264">H.264</option>
              <option value="h265">H.265</option>
              <option value="mjpeg">MJPEG</option>
            {:else if formProtocol === 'http'}
              <option value="jpeg">JPEG</option>
            {:else if formProtocol === 'onvif'}
              <option value="">{t('cameras.autoDetect')}</option>
              <option value="h264">H.264</option>
              <option value="h265">H.265</option>
            {/if}
          </select>
        </div>

        <!-- URL -->
        <div>
          <label for="cam-url" class="input-label">
            {t('cameras.url')}
            {#if formProtocol === 'onvif'}
              <span class="text-xs th-text-muted ml-1">({t('cameras.onvifEndpoint')})</span>
            {/if}
          </label>
          <div class="flex gap-1">
            <input id="cam-url" type="text" class="input flex-1 min-w-0 {validationErrors['url'] ? 'border-red-500' : ''}" bind:value={formUrl}
              placeholder={formProtocol === 'onvif' ? 'http://192.168.1.100:80/onvif/device_service' : 'rtsp://...'}
              on:blur={() => validateField('url', formUrl)} on:input={() => { if (validationErrors['url']) delete validationErrors['url']; }} />
            {#if formProtocol === 'onvif'}
            <button type="button" class="btn btn-ghost btn-sm shrink-0 px-1.5" on:click={probeONVIF} disabled={detailLoading}>
              {#if detailLoading}
                <span class="spinner"></span>
              {:else}
                {t('onvif.viewDetails')}
              {/if}
            </button>
            {/if}
          </div>
          {#if validationErrors['url']}
            <p class="th-color-danger text-xs mt-1">{validationErrors['url']}</p>
          {/if}
          {#if deviceDetail}
            <div class="mt-2 p-2 rounded th-bg-hover text-sm th-text-secondary">
              {#if deviceDetail.device_info?.manufacturer}
                <span>{deviceDetail.device_info.manufacturer} {deviceDetail.device_info.model || ''}</span>
              {/if}
              {#if deviceDetail.profiles?.length}
                <div class="mt-1">
                  <label class="text-xs th-text-muted">{t('cameras.profile')}</label>
                  <select class="input py-0.5 text-sm mt-0.5" bind:value={selectedProfileToken}>
                    {#each deviceDetail.profiles as p}
                      <option value={p.token}>{p.name || p.token} ({p.encoding || '?'})</option>
                    {/each}
                  </select>
                </div>
              {/if}
            </div>
          {/if}
        </div>

        <!-- Username -->
        <div>
          <label for="cam-user" class="input-label">{t('cameras.username')}</label>
          <input id="cam-user" type="text" class="input" bind:value={formUsername} placeholder={camera ? (camera.username || t('cameras.notSet')) : ''} />
        </div>

        <!-- Password -->
        <div>
          <label for="cam-pass" class="input-label">{t('cameras.password')}</label>
          <div class="relative">
            <input
              id="cam-pass"
              type={showPassword ? 'text' : 'password'}
              class="input pr-10"
              bind:value={formPassword}
              placeholder={camera ? (camera.has_password ? t('cameras.passwordSet') : t('cameras.notSet')) : ''}
            />
            <button
              type="button"
              class="absolute right-2 top-1/2 -translate-y-1/2 th-text-tertiary hover:th-text-primary transition-colors"
              on:click={() => showPassword = !showPassword}
              aria-label={showPassword ? t('common.hidePassword') : t('common.showPassword')}
            >
              {#if showPassword}
                <EyeOff class="w-4 h-4" />
              {:else}
                <Eye class="w-4 h-4" />
              {/if}
            </button>
          </div>
        </div>

        <!-- Description -->
        <div class="md:col-span-3">
          <label for="cam-desc" class="input-label">{t('cameras.description')}</label>
          <textarea id="cam-desc" class="input" rows="2" bind:value={formDescription} placeholder={t('cameras.descriptionPlaceholder')}></textarea>
        </div>

        <!-- Location -->
        <div>
          <label for="cam-location" class="input-label">{t('cameras.location')}</label>
          <input id="cam-location" type="text" class="input" bind:value={formLocation} placeholder={t('cameras.locationPlaceholder')} />
        </div>

        <!-- Brand -->
        <div>
          <label for="cam-brand" class="input-label">{t('cameras.brand')}</label>
          <input id="cam-brand" type="text" class="input" bind:value={formBrand} />
        </div>

        <!-- Model -->
        <div>
          <label for="cam-model" class="input-label">{t('cameras.model')}</label>
          <input id="cam-model" type="text" class="input" bind:value={formModel} />
        </div>

        <!-- Serial Number -->
        <div>
          <label for="cam-serial" class="input-label">{t('cameras.serialNumber')}</label>
          <input id="cam-serial" type="text" class="input" bind:value={formSerialNumber} />
        </div>

        <!-- Retention Days -->
        <div>
          <label for="cam-retention" class="input-label">{t('cameras.retentionDays')}</label>
          <input id="cam-retention" type="number" min="0" class="input" bind:value={formRetentionDays} />
          <p class="th-text-muted text-xs mt-1">{t('cameras.retentionDaysHint')}</p>
        </div>

        <!-- Enabled -->
        <div class="flex items-center md:items-end md:pb-1.5 gap-2">
          <input id="cam-enabled" type="checkbox" class="accent-[var(--color-accent)]" bind:checked={formEnabled} />
          <label for="cam-enabled" class="th-text-secondary text-sm">{t('cameras.enabledToggle')}</label>
        </div>
      </div>

      <!-- Merge Config (edit mode only) -->
      {#if camera}
        <details class="mt-6 border th-border rounded-lg"
          open={mergeConfig ? true : undefined}
        >
          <summary class="px-4 py-3 cursor-pointer th-text-secondary hover:th-text-primary transition-colors font-medium select-none">
            {t('merge.title')}
            {#if mergeConfig}
              <span class="text-xs th-text-muted ml-2">{t('merge.customized')}</span>
            {:else}
              <span class="text-xs th-text-muted ml-2">{t('merge.usingDefault')}</span>
            {/if}
          </summary>

          <div class="px-4 pb-4 pt-2">
            {#if mergeConfigLoading}
              <div class="flex items-center gap-2 py-4 th-text-muted">
                <span class="spinner"></span>
                <span class="text-sm">{t('common.loading')}</span>
              </div>
            {:else}
              <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
                <div class="flex items-center gap-2">
                  <input
                    id="merge-enabled"
                    type="checkbox"
                    class="accent-[var(--color-accent)]"
                    checked={mergeConfig?.enabled !== false}
                    on:change={(e) => {
                      if (!mergeConfig) mergeConfig = {};
                      mergeConfig.enabled = (e.target as HTMLInputElement).checked;
                    }}
                  />
                  <label for="merge-enabled" class="th-text-secondary text-sm">{t('merge.enableMerge')}</label>
                </div>
                <div>
                  <label for="merge-check-interval" class="input-label">{t('merge.checkInterval')}</label>
                  <select
                    id="merge-check-interval"
                    class="input"
                    value={mergeConfig?.check_interval || '1h'}
                    on:change={(e) => {
                      if (!mergeConfig) mergeConfig = {};
                      mergeConfig.check_interval = (e.target as HTMLSelectElement).value;
                    }}
                  >
                    <option value="30m">{t('merge.30m')}</option>
                    <option value="1h">{t('merge.1h')}</option>
                    <option value="2h">{t('merge.2h')}</option>
                    <option value="6h">{t('merge.6h')}</option>
                  </select>
                </div>
                <div>
                  <label for="merge-window" class="input-label">{t('merge.windowSize')}</label>
                  <select
                    id="merge-window"
                    class="input"
                    value={mergeConfig?.window_size || '30m'}
                    on:change={(e) => {
                      if (!mergeConfig) mergeConfig = {};
                      mergeConfig.window_size = (e.target as HTMLSelectElement).value;
                    }}
                  >
                    <option value="30m">{t('merge.30m')}</option>
                    <option value="1h">{t('merge.1h')}</option>
                    <option value="2h">{t('merge.2h')}</option>
                  </select>
                </div>
                <div>
                  <label for="merge-batch" class="input-label">{t('merge.batchLimit')}</label>
                  <input
                    id="merge-batch"
                    type="number"
                    class="input"
                    min="10"
                    max="1000"
                    value={mergeConfig?.batch_limit || 100}
                    on:input={(e) => {
                      if (!mergeConfig) mergeConfig = {};
                      mergeConfig.batch_limit = Number((e.target as HTMLInputElement).value);
                    }}
                  />
                </div>
                <div>
                  <label for="merge-age" class="input-label">{t('merge.minSegmentAge')}</label>
                  <select
                    id="merge-age"
                    class="input"
                    value={mergeConfig?.min_segment_age || '5m'}
                    on:change={(e) => {
                      if (!mergeConfig) mergeConfig = {};
                      mergeConfig.min_segment_age = (e.target as HTMLSelectElement).value;
                    }}
                  >
                    <option value="5m">{t('merge.5m')}</option>
                    <option value="10m">{t('merge.10m')}</option>
                    <option value="30m">{t('merge.30m')}</option>
                    <option value="1h">{t('merge.1h')}</option>
                  </select>
                </div>
                <div>
                  <label for="merge-min-segments" class="input-label">{t('merge.minSegmentsToMerge')}</label>
                  <input
                    id="merge-min-segments"
                    type="number"
                    class="input"
                    min="2"
                    max="50"
                    value={mergeConfig?.min_segments_to_merge || 3}
                    on:input={(e) => {
                      if (!mergeConfig) mergeConfig = {};
                      mergeConfig.min_segments_to_merge = Number((e.target as HTMLInputElement).value);
                    }}
                  />
                </div>
              </div>

              <div class="mt-4 flex justify-end">
                <button
                  type="button"
                  class="btn btn-ghost btn-sm"
                  on:click={async () => {
                    if (!camera) return;
                    try {
                      await deleteCameraMergeConfig(camera.id);
                      mergeConfig = null;
                      showToast(t('merge.restoredDefault'), 'success');
                    } catch {
                      showToast(t('merge.operationFailed'), 'error');
                    }
                  }}
                >
                  {t('merge.useGlobalDefault')}
                </button>
              </div>
            {/if}
          </div>
        </details>
      {/if}

      <div class="flex items-center gap-3 mt-6">
        <button on:click={handleSubmit} class="btn btn-primary" disabled={saving}>
          {#if saving}
            <span class="spinner mr-2"></span>
          {/if}
          {t('cameras.save')}
        </button>
        <button on:click={handleCancel} class="btn btn-ghost">
          {t('cameras.cancel')}
        </button>
      </div>
    </div>
  </div>
{/if}
