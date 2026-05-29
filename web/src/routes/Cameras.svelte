<script lang="ts">
  import { onMount } from 'svelte';
  import { listCameras, updateCamera, deleteCamera, discoverONVIFDevices, createCamera } from '$lib/api';
  import { isAdmin } from '$lib/api';
  import type { Camera, DiscoveredDevice } from '$lib/api';
  import { t } from '$lib/i18n';
  import { Eye, Pencil, X, Camera as CameraIcon, AlertCircle, TriangleAlert } from 'lucide-svelte';
  import { showToast } from '$lib/toast';
  import { fade, fly } from 'svelte/transition';
  import CameraFormModal from '$components/CameraFormModal.svelte';

  function getConnectionStatus(status: string | undefined): { text: string; color: string; reason: string } {
    switch (status) {
      case 'recording':
        return { text: t('cameras.online'), color: 'badge-success', reason: '' };
      case 'reconnecting':
        return { text: t('cameras.connecting'), color: 'badge-warning', reason: '' };
      case 'stopped':
        return { text: t('cameras.offline'), color: 'badge-neutral', reason: t('cameras.statusStopped') };
      case 'error':
        return { text: t('cameras.offline'), color: 'badge-neutral', reason: t('cameras.statusError') };
      default:
        return { text: t('cameras.offline'), color: 'badge-neutral', reason: status ? t('cameras.statusStopped') : '' };
    }
  }

  function isRecording(status: string | undefined): boolean {
    return status === 'recording';
  }

  let cameras = $state<Camera[]>([]);
  let loading = $state(true);
  let error = $state('');

  // Modal state
  let showFormModal = $state(false);
  let editingCamera = $state<Camera | null>(null);

  // Inline name edit
  let editingNameId = $state<string | null>(null);
  let inlineName = $state('');

  // Delete confirmation
  let deletingCamera = $state<Camera | null>(null);

  // ONVIF discovery
  let showOnvifModal = $state(false);
  let scanning = $state(false);
  let scanDone = $state(false);
  let scanError = $state('');
  let discoveredDevices = $state<DiscoveredDevice[]>([]);
  let addingDeviceId = $state<string | null>(null);
  let onvifUsername = $state('');
  let onvifPassword = $state('');

  function openAddForm() {
    editingCamera = null;
    showFormModal = true;
  }

  function openEditForm(camera: Camera) {
    editingCamera = camera;
    showFormModal = true;
  }

  function handleFormCancel() {
    showFormModal = false;
    editingCamera = null;
  }

  async function handleFormSaved() {
    showFormModal = false;
    editingCamera = null;
    await loadCameras();
  }

  async function loadCameras() {
    loading = true;
    error = '';
    try {
      cameras = await listCameras();
    } catch (e) {
      error = e instanceof Error ? e.message : t('cameras.failedLoad');
    } finally {
      loading = false;
    }
  }

  function handleDeleteCancel() {
    deletingCamera = null;
  }

  function handleGlobalKeydown(e: KeyboardEvent) {
    if (e.key === 'Escape') {
      if (deletingCamera) handleDeleteCancel();
      else if (showOnvifModal) showOnvifModal = false;
    }
  }

  async function handleDelete() {
    if (!deletingCamera) return;
    try {
      await deleteCamera(deletingCamera.id);
      showToast(t('cameras.cameraDeleted'), 'success');
      deletingCamera = null;
      await loadCameras();
    } catch (e) {
      showToast(t('cameras.failedDelete'), 'error');
      deletingCamera = null;
    }
  }

  function startInlineEdit(camera: Camera) {
    editingNameId = camera.id;
    inlineName = camera.name;
  }

  async function saveInlineEdit(camera: Camera) {
    if (!inlineName.trim()) { editingNameId = null; return; }
    try {
      await updateCamera(camera.id, { name: inlineName.trim() });
      camera.name = inlineName.trim();
      showToast(t('cameras.nameUpdated'), 'success');
    } catch (e) {
      showToast(t('cameras.failedUpdate'), 'error');
    }
    editingNameId = null;
  }

  function cancelInlineEdit() {
    editingNameId = null;
  }

  function openOnvifModal() {
    showOnvifModal = true;
    scanONVIF();
  }

  function scanONVIF() {
    scanning = true;
    scanError = '';
    discoveredDevices = [];
    scanDone = false;
    discoverONVIFDevices(5).then(results => {
      const existingEndpoints = new Set(
        cameras.filter(c => c.protocol === 'onvif' && c.url).map(c => c.url)
      );
      discoveredDevices = results.filter(d => {
        const ep = d.endpoint || (d.xaddrs.length > 0 ? d.xaddrs[0] : '');
        return !existingEndpoints.has(ep);
      });
    }).catch(e => {
      scanError = e instanceof Error ? e.message : String(e);
    }).finally(() => {
      scanning = false;
      scanDone = true;
    });
  }



  async function addDiscoveredDevice(device: DiscoveredDevice) {
    addingDeviceId = device.uuid;
    try {
      await createCamera({
        name: device.name || t('onvif.deviceName'),
        protocol: 'onvif',
        url: device.endpoint || (device.xaddrs.length > 0 ? device.xaddrs[0] : ''),
        enabled: true,
        username: onvifUsername || undefined,
        password: onvifPassword || undefined,
      });
      showToast(t('cameras.cameraAdded'), 'success');
      discoveredDevices = discoveredDevices.filter(d => d.uuid !== device.uuid);
      await loadCameras();
    } catch (e) {
      showToast(t('cameras.failedAdd'), 'error');
    } finally {
      addingDeviceId = null;
    }
  }

  onMount(() => {
    loadCameras();
  });
</script>

<svelte:window onkeydown={handleGlobalKeydown} />
<div class="min-h-screen th-bg-primary pt-[68px]">

  <!-- Main content -->
  <main class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
    <div class="flex items-center justify-between mb-6">
      <h2 class="text-2xl font-bold th-text-primary">{t('cameras.title')}</h2>
      <div class="flex gap-3">
        {#if isAdmin()}
        <button on:click={openOnvifModal} class="btn btn-ghost">
          {t('onvif.discover')}
        </button>
        <button on:click={openAddForm} class="btn btn-primary">
          + {t('cameras.addCamera')}
        </button>
        {/if}
      </div>
    </div>

    <!-- Error -->
    {#if error}
      <div class="card border th-border-danger p-8 text-center">
        <div class="flex justify-center mb-4 th-color-danger">
          <AlertCircle size={48} />
        </div>
        <h3 class="text-lg font-medium th-text-primary mb-2">{t('common.error')}</h3>
        <p class="th-text-secondary mb-4">{error}</p>
        <button on:click={loadCameras} class="btn btn-primary btn-sm">{t('common.retry')}</button>
      </div>
    {/if}

    <!-- Loading -->
    {#if loading}
      <div class="card border th-border">
        <div class="p-6 space-y-4">
          {#each Array(3) as _}
            <div class="flex gap-4 items-center">
              <div class="h-4 w-32 th-bg-tertiary rounded animate-pulse"></div>
              <div class="h-4 w-20 th-bg-tertiary rounded animate-pulse"></div>
              <div class="h-4 w-16 th-bg-tertiary rounded animate-pulse"></div>
              <div class="h-4 w-40 th-bg-tertiary rounded animate-pulse"></div>
            </div>
          {/each}
        </div>
      </div>
    {:else}
      <div class="space-y-6">

        <!-- ONVIF Discovery Modal -->
        {#if showOnvifModal}
          <div
            class="fixed inset-0 z-50 flex items-start justify-center pt-[10vh]"
            role="dialog"
            aria-modal="true"
          >
            <div class="fixed inset-0 bg-black/50 backdrop-blur-sm" on:click={() => showOnvifModal = false} transition:fade={{ duration: 150 }}></div>
            <div
              class="relative w-full max-w-2xl mx-4 max-h-[75vh] overflow-y-auto card p-6 border th-border th-bg-primary"
              transition:fly={{ y: 20, duration: 200 }}
            >
              <div class="flex items-center justify-between mb-4">
                <h3 class="text-lg font-semibold th-text-primary">{t('onvif.discover')}</h3>
                <button on:click={() => showOnvifModal = false} class="btn btn-ghost p-1" aria-label={t('cameras.cancel')}>
                  <X size={20} />
                </button>
              </div>
              <!-- ONVIF Credentials -->
              <div class="grid grid-cols-1 sm:grid-cols-3 gap-3 mb-4 items-end">
                <div>
                  <label class="input-label text-xs">{t('onvif.username')}</label>
                  <input type="text" class="input py-1 text-sm" bind:value={onvifUsername} placeholder="admin" />
                </div>
                <div>
                  <label class="input-label text-xs">{t('onvif.password')}</label>
                  <input type="password" class="input py-1 text-sm" bind:value={onvifPassword} placeholder="******" />
                </div>
                <div class="flex items-center">
                  <span class="text-xs th-text-muted">{t('onvif.credentialsHint')}</span>
                </div>
              </div>
              {#if scanning}
                <div class="flex items-center gap-3 th-text-secondary py-4">
                  <span class="spinner"></span>
                  <span>{t('onvif.discovering')}</span>
                </div>
              {:else if scanError}
                <div class="th-color-danger text-sm py-2">{scanError}</div>
              {:else if discoveredDevices.length === 0}
                <p class="th-text-secondary text-sm py-2">{t('onvif.noDevices')}</p>
              {:else}
                <div class="space-y-3">
                  {#each discoveredDevices as device (device.uuid)}
                    <div class="flex items-center justify-between p-4 rounded-md th-bg-hover border th-border">
                      <div class="min-w-0 flex-1 mr-4">
                        <div class="font-medium th-text-primary truncate">{device.name || t('onvif.deviceName')}</div>
                        <div class="text-sm th-text-secondary truncate">{device.endpoint}</div>
                        {#if device.hardware}
                          <div class="text-xs th-text-muted mt-0.5">{device.hardware}</div>
                        {/if}
                      </div>
                      {#if isAdmin()}
                      <button
                        on:click={() => addDiscoveredDevice(device)}
                        class="btn btn-primary btn-sm shrink-0"
                        disabled={addingDeviceId === device.uuid}
                      >
                        {#if addingDeviceId === device.uuid}
                          <span class="spinner mr-1"></span>
                        {/if}
                        {t('onvif.addCamera')}
                      </button>
                      {/if}
                    </div>
                  {/each}
                </div>
              {/if}
              {#if !scanning && scanDone}
                <div class="mt-4 flex justify-end">
                  <button on:click={scanONVIF} class="btn btn-ghost btn-sm">
                    {t('onvif.discover')}
                  </button>
                </div>
              {/if}
            </div>
          </div>
        {/if}

        <!-- Add/Edit Modal -->
        <CameraFormModal
          show={showFormModal}
          bind:camera={editingCamera}
          oncancel={handleFormCancel}
          onsaved={handleFormSaved}
        />

        <!-- Delete Confirmation Modal -->
        {#if deletingCamera}
          <div
            class="fixed inset-0 z-50 flex items-start justify-center pt-[25vh]"
            role="dialog"
            aria-modal="true"
          >
            <div class="fixed inset-0 bg-black/50 backdrop-blur-sm" on:click={handleDeleteCancel} transition:fade={{ duration: 150 }}></div>
            <div
              class="relative w-full max-w-sm mx-4 card p-6 border th-border th-bg-primary"
              transition:fly={{ y: 20, duration: 200 }}
            >
              <div class="flex items-center justify-between mb-4">
                <div class="flex items-center gap-2 th-color-danger">
                  <TriangleAlert size={22} />
                  <h3 class="text-lg font-semibold">{t('cameras.deleteTitle')}</h3>
                </div>
                <button on:click={handleDeleteCancel} class="btn btn-ghost p-1" aria-label={t('cameras.cancel')}>
                  <X size={20} />
                </button>
              </div>
              <p class="th-text-secondary mb-6">
                {t('cameras.deleteMessage', { name: deletingCamera.name })}
              </p>
              <div class="flex items-center justify-end gap-3">
                <button on:click={handleDeleteCancel} class="btn btn-ghost">
                  {t('cameras.cancel')}
                </button>
                <button on:click={handleDelete} class="px-4 py-2 th-bg-danger hover:th-bg-danger-light text-white rounded-md transition-colors font-medium text-sm">
                  {t('cameras.deleteConfirm')}
                </button>
              </div>
            </div>
          </div>
        {/if}

        <!-- Camera Table -->
        <div class="card border th-border overflow-hidden">
          {#if cameras.length === 0}
            <div class="p-12 text-center">
              <div class="flex justify-center mb-4 th-text-muted">
                <CameraIcon size={48} />
              </div>
              <h3 class="text-lg font-medium th-text-primary mb-2">{t('cameras.noCameras')}</h3>
              <p class="text-sm th-text-muted mb-4">{t('cameras.noCamerasHint')}</p>
              {#if isAdmin()}
              <button on:click={openAddForm} class="btn btn-primary btn-sm">+ {t('cameras.addCamera')}</button>
              {/if}
            </div>
          {:else}
            <div class="overflow-x-auto">
              <table class="min-w-full divide-y divide-[var(--border)]">
                <thead>
                  <tr>
                    <th class="px-6 py-3 text-left text-xs font-medium th-text-muted uppercase tracking-wider">{t('cameras.tableName')}</th>
                    <th class="px-6 py-3 text-left text-xs font-medium th-text-muted uppercase tracking-wider">{t('cameras.tableStatus')}</th>
                    <th class="px-6 py-3 text-left text-xs font-medium th-text-muted uppercase tracking-wider">{t('cameras.tableRecording')}</th>
                    <th class="px-6 py-3 text-left text-xs font-medium th-text-muted uppercase tracking-wider">{t('cameras.tableProtocol')}</th>
                    <th class="px-6 py-3 text-left text-xs font-medium th-text-muted uppercase tracking-wider">{t('cameras.tableEncoding')}</th>
                    <th class="px-6 py-3 text-left text-xs font-medium th-text-muted uppercase tracking-wider">{t('cameras.tableUrl')}</th>
                    <th class="px-6 py-3 text-left text-xs font-medium th-text-muted uppercase tracking-wider">{t('cameras.tableActions')}</th>
                  </tr>
                </thead>
                <tbody class="divide-y divide-[var(--border)]">
                  {#each cameras as camera (camera.id)}
                    <tr class="hover:th-bg-hover transition-colors">
                      <td class="px-6 py-4 whitespace-nowrap text-sm">
                        {#if editingNameId === camera.id}
                          <input
                            type="text"
                            class="input py-0.5 px-2 text-sm w-40"
                            bind:value={inlineName}
                            on:keydown={(e) => {
                              if (e.key === 'Enter') saveInlineEdit(camera);
                              if (e.key === 'Escape') cancelInlineEdit();
                            }}
                            on:blur={() => saveInlineEdit(camera)}
                            focus
                          />
                        {:else}
                          <button
                            class="font-medium th-text-primary hover:underline cursor-pointer flex items-center gap-1"
                            on:click={() => startInlineEdit(camera)}
                            title={t('cameras.editName')}
                          >
                            {camera.name}
                            <Pencil size={12} class="th-text-tertiary" />
                          </button>
                        {/if}
                      </td>
                      <td class="px-6 py-4 whitespace-nowrap text-sm">
                        <span class="badge {getConnectionStatus(camera.status).color}">{getConnectionStatus(camera.status).text}</span>
                        {#if getConnectionStatus(camera.status).reason}
                          <div class="text-xs th-text-muted mt-0.5">{getConnectionStatus(camera.status).reason}</div>
                        {/if}
                      </td>
                      <td class="px-6 py-4 whitespace-nowrap text-sm">
                        {#if isRecording(camera.status)}
                          <span class="badge badge-success">{t('cameras.statusRecording')}</span>
                        {:else}
                          <span class="text-xs th-text-muted">-</span>
                        {/if}
                      </td>
                      <td class="px-6 py-4 whitespace-nowrap text-sm th-text-secondary">{t('cameras.protocol.' + camera.protocol) || camera.protocol}</td>
                      <td class="px-6 py-4 whitespace-nowrap text-sm th-text-secondary">{camera.encoding ? (t('cameras.encoding.' + camera.encoding) || camera.encoding) : '-'}</td>
                      <td class="px-6 py-4 text-sm th-text-secondary max-w-xs truncate">{camera.url}</td>
                      <td class="px-6 py-4 whitespace-nowrap text-sm">
                        <div class="flex gap-2 items-center">
                          {#if camera.protocol === 'rtsp' || camera.protocol === 'onvif' || camera.protocol === 'rtsp_h264' || camera.protocol === 'rtsp_h265'}
                            <a
                              href="#/live/{camera.id}"
                              class="btn btn-primary px-2 py-1 text-sm flex items-center gap-1"
                              title={t('cameras.live')}
                            >
                              <Eye size={14} />
                              {t('cameras.live')}
                            </a>
                          {/if}
                          {#if isAdmin()}
                          <button
                            on:click={() => openEditForm(camera)}
                            class="btn btn-ghost px-2 py-1 text-sm transition-all duration-200"
                          >{t('cameras.edit')}</button>
                          <button
                            on:click={() => deletingCamera = camera}
                            class="btn btn-ghost px-2 py-1 text-sm th-color-danger transition-all duration-200"
                          >{t('cameras.delete')}</button>
                          {/if}
                        </div>
                      </td>
                    </tr>
                  {/each}
                </tbody>
              </table>
            </div>
          {/if}
        </div>

      </div>
    {/if}
  </main>
</div>
