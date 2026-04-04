import express from 'express';
import https from 'https';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
import { readFileSync } from 'fs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const app = express();
const PORT = 3456;

// Allow large base64 payloads
app.use(express.json({ limit: '50mb' }));

// Serve static HTML
app.get('/', (req, res) => {
  res.sendFile(join(__dirname, 'index.html'));
});

// History: keep last 3 responses
const history = [];

// Proxy to Ollama vision API (streaming)
app.post('/api/analyze', async (req, res) => {
  const { image, prompt: clientPrompt } = req.body;

  let basePrompt = clientPrompt || 'この画像を短く説明して。日本語で1文。';

  // Add history context
  if (history.length > 0) {
    const historyText = history.map((h, i) => `${i + 1}回前: ${h}`).join('\n');
    basePrompt += `\n\n過去の観察:\n${historyText}\n\n上記と違う点や変化に注目して。同じことは言わないで。`;
  }

  const prompt = basePrompt;

  try {
    const ollamaRes = await fetch('http://localhost:11434/api/generate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: 'gemma4:e2b',
        prompt,
        images: [image],
        stream: true,
      }),
    });

    if (!ollamaRes.ok) {
      const errText = await ollamaRes.text();
      return res.status(500).json({ error: `Ollama error: ${errText}` });
    }

    // Stream SSE to client
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection', 'keep-alive');

    const reader = ollamaRes.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let fullResponse = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop();
      for (const line of lines) {
        if (!line.trim()) continue;
        try {
          const chunk = JSON.parse(line);
          if (chunk.response) {
            fullResponse += chunk.response;
            res.write(`data: ${JSON.stringify({ text: chunk.response })}\n\n`);
          }
          if (chunk.done) {
            res.write(`data: ${JSON.stringify({ done: true })}\n\n`);
          }
        } catch {}
      }
    }

    // Save to history (max 3)
    if (fullResponse) {
      history.push(fullResponse);
      if (history.length > 3) history.shift();
    }

    res.end();
  } catch (err) {
    console.error('Ollama request failed:', err.message);
    res.status(500).json({ error: err.message });
  }
});

const sslOptions = {
  key: readFileSync(join(__dirname, 'key.pem')),
  cert: readFileSync(join(__dirname, 'cert.pem')),
};

https.createServer(sslOptions, app).listen(PORT, '0.0.0.0', () => {
  console.log(`HTTPS server running at https://100.103.192.117:${PORT}`);
});
