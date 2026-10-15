// Development tool for the failure drills. It plays the browser.
//   node infra/drill-client.mjs hold <userId> <port[,port...]>             stay connected, print frames, reconnect through the port list
//   node infra/drill-client.mjs send <userId> <port> <conv> <clientMsgId> <body>   send one message, print the reply, exit
//   node infra/drill-client.mjs raw <userId> <port> <text>                 send one raw text frame, print the reply, exit
//   node infra/drill-client.mjs many <count> <port>                        open <count> sockets (users load-0...) and hold them
//   node infra/drill-client.mjs churn <count> <port>                       open and close <count> sockets quickly
//   node infra/drill-client.mjs flood <userId> <port> <conv> <count>       send <count> messages as fast as possible

const [mode, ...args] = process.argv.slice(2);
const url = (port, user) => `ws://localhost:${port}/ws?userId=${encodeURIComponent(user)}`;
const now = () => new Date().toISOString().slice(11, 23);

function hold(user, ports) {
  const list = ports.split(',');
  let attempt = 0;
  const seen = [];
  const open = () => {
    const port = list[attempt++ % list.length];
    const ws = new WebSocket(url(port, user));
    ws.onopen = () => console.log(now(), `connected to ${port}`);
    ws.onmessage = (e) => {
      const f = JSON.parse(e.data);
      if (f.type === 'message') {
        seen.push(f.message.seq);
        console.log(now(), 'message seq', f.message.seq, JSON.stringify(f.message.body).slice(0, 40));
      } else console.log(now(), e.data.slice(0, 120));
    };
    ws.onclose = (e) => {
      console.log(now(), `closed ${port} code=${e.code}; seqs seen so far: ${seen.join(',')}`);
      setTimeout(open, 1000);
    };
    ws.onerror = () => {};
  };
  open();
}

function once(user, port, frame) {
  const ws = new WebSocket(url(port, user));
  ws.onmessage = (e) => {
    const f = JSON.parse(e.data);
    if (f.type === 'ready') ws.send(frame);
    else if (f.type === 'ack' || f.type === 'error') { console.log(now(), e.data); ws.close(); }
  };
  // The close code matters: an oversized frame is refused by closing the socket (1009), not by an error frame.
  ws.onclose = (e) => { console.log(now(), `closed code=${e.code} reason=${e.reason || '(none)'}`); process.exit(0); };
  ws.onerror = () => { console.log(now(), 'connection error'); };
  setTimeout(() => { console.log(now(), 'no reply in 15 s'); process.exit(2); }, 15000);
}

function many(count, port) {
  let open = 0;
  for (let i = 0; i < count; i++) {
    const ws = new WebSocket(url(port, `load-${i}`));
    ws.onopen = () => { open++; };
    ws.onclose = () => { open--; };
    ws.onerror = () => {};
  }
  setInterval(() => console.log(now(), 'open sockets:', open), 5000);
}

async function churn(count, port) {
  let ok = 0, failed = 0;
  await Promise.all(Array.from({ length: count }, (_, i) => new Promise((resolve) => {
    const ws = new WebSocket(url(port, `churn-${i}`));
    ws.onopen = () => { ok++; setTimeout(() => ws.close(), Math.random() * 300); };
    ws.onclose = () => resolve();
    ws.onerror = () => { failed++; resolve(); };
  })));
  console.log(now(), `connected ${ok}, failed ${failed}`);
  process.exit(0);
}

function flood(user, port, conv, count) {
  const ws = new WebSocket(url(port, user));
  let acks = 0, errors = 0, start = 0;
  ws.onmessage = (e) => {
    const f = JSON.parse(e.data);
    if (f.type === 'ready') {
      start = Date.now();
      for (let i = 0; i < count; i++) {
        ws.send(JSON.stringify({ type: 'send', conversationId: conv, clientMsgId: crypto.randomUUID(), body: `flood ${i}` }));
      }
    } else if (f.type === 'ack') acks++;
    else if (f.type === 'error') errors++;
    if (acks + errors === count) {
      const s = (Date.now() - start) / 1000;
      console.log(now(), `acks ${acks} errors ${errors} in ${s}s = ${(acks / s).toFixed(1)}/s`);
      process.exit(0);
    }
  };
  ws.onerror = () => process.exit(1);
  setTimeout(() => { console.log(now(), `timeout: acks ${acks} errors ${errors}`); process.exit(2); }, 120000);
}

const modes = {
  hold: () => hold(args[0], args[1]),
  send: () => once(args[0], args[1], JSON.stringify({ type: 'send', conversationId: args[2], clientMsgId: args[3], body: args[4] })),
  raw: () => once(args[0], args[1], args[2]),
  many: () => many(Number(args[0]), args[1]),
  churn: () => churn(Number(args[0]), args[1]),
  flood: () => flood(args[0], args[1], args[2], Number(args[3])),
};
(modes[mode] ?? (() => console.log('unknown mode: ' + mode)))();
