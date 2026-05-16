import BpmnViewer from 'bpmn-js';

const viewer = new BpmnViewer({
  container: '#canvas',
});

const generateBtn = document.getElementById('generate-btn') as HTMLButtonElement;
const descriptionEl = document.getElementById('process-description') as HTMLTextAreaElement;
const progressContainer = document.getElementById('progress-container')!;
const progressList = document.getElementById('progress-list')!;
const diagnosticsContainer = document.getElementById('diagnostics-container')!;
const diagnosticsList = document.getElementById('diagnostics-list')!;
const downloadContainer = document.getElementById('download-container')!;
const downloadXml = document.getElementById('download-xml') as HTMLAnchorElement;

let eventSource: EventSource | null = null;
let currentXml = '';
let currentDownloadUrl: string | null = null;

generateBtn.addEventListener('click', async () => {
  const desc = descriptionEl.value.trim();
  if (!desc) return;

  generateBtn.disabled = true;
  progressContainer.classList.remove('hidden');
  progressList.innerHTML = '';
  diagnosticsContainer.classList.add('hidden');
  downloadContainer.classList.add('hidden');
  viewer.clear();

  if (eventSource) {
    eventSource.close();
  }

  try {
    const res = await fetch('/api/bpmn/generations', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ processDescription: desc }),
    });

    if (!res.ok) {
      throw new Error(`Failed to start generation: ${res.statusText}`);
    }

    const data = await res.json();
    connectSse(data.sseUrl);
  } catch (e: any) {
    addProgress(`Error: ${e.message}`);
    generateBtn.disabled = false;
  }
});

function connectSse(url: string) {
  eventSource = new EventSource(url);

  eventSource.onmessage = async (e) => {
    let event;
    try {
      event = JSON.parse(e.data);
    } catch (err) {
      console.error('Failed to parse SSE message', err);
      return;
    }

    if (event.type === 'ProgressUpdateEvent') {
      addProgress(event.name);
    } else if (event.type === 'BpmnSnapshotEvent') {
      await handleSnapshot(event);
    } else if (event.type === 'AgentProcessFinishedEvent') {
      addProgress('Process complete.');
      if (currentXml) {
          setupDownload(currentXml);
      }
      generateBtn.disabled = false;
      eventSource?.close();
    } else if (event.type === 'AgentProcessFailedEvent') {
      addProgress('Process failed.');
      generateBtn.disabled = false;
      eventSource?.close();
    }
  };

  eventSource.onerror = (e) => {
    console.error('SSE Error', e);
    eventSource?.close();
    generateBtn.disabled = false;
    addProgress('Connection lost.');
  };
}

function addProgress(msg: string) {
  const li = document.createElement('li');
  li.textContent = msg;
  progressList.appendChild(li);
}

async function handleSnapshot(event: any) {
  currentXml = event.xml;
  if (!currentXml) return;

  try {
    const { warnings } = await viewer.importXML(currentXml);
    if (warnings.length) {
      console.warn('Warnings importing XML', warnings);
    }

    viewer.get('canvas').zoom('fit-viewport');

    renderDiagnostics(event.diagnostics || []);
  } catch (err) {
    console.error('Error rendering XML', err);
  }
}

function renderDiagnostics(diagnostics: any[]) {
  diagnosticsList.innerHTML = '';
  const overlays = viewer.get('overlays') as any;

  // Clear previous overlays
  overlays.remove({ type: 'diagnostic' });

  if (diagnostics.length === 0) {
    diagnosticsContainer.classList.add('hidden');
    return;
  }

  diagnosticsContainer.classList.remove('hidden');

  diagnostics.forEach(diag => {
    const li = document.createElement('li');
    li.className = 'diagnostic-item';
    li.textContent = `[${diag.source}] ${diag.message}`;
    diagnosticsList.appendChild(li);

    // If we have an element ID, we can overlay it
    const elementId = diag.elementId || diag.objectRef;
    if (elementId) {
      try {
        overlays.add(elementId, 'diagnostic', {
          position: {
            bottom: 0,
            right: 0
          },
          html: `<div class="diagnostic-overlay" style="width: 10px; height: 10px; background: red; border-radius: 50%; cursor: help;" title="${diag.message.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')}"></div>`
        });
      } catch (e) {
        // Element might not exist in the diagram yet
      }
    }
  });
}

function setupDownload(xml: string) {
    downloadContainer.classList.remove('hidden');
    if (currentDownloadUrl) {
      URL.revokeObjectURL(currentDownloadUrl);
    }
    const blob = new Blob([xml], { type: 'application/bpmn20-xml' });
    currentDownloadUrl = URL.createObjectURL(blob);
    downloadXml.href = currentDownloadUrl;
}
