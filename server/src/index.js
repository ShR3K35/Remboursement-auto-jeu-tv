import Stripe from 'stripe';
import { loadConfig } from './config.js';
import { createApp } from './app.js';
import { createMailevaClient } from './maileva.js';
import { createStore } from './store.js';

const config = loadConfig();
const store = createStore(config.dataDir);
await store.init();

const app = createApp({
  config,
  stripe: new Stripe(config.stripe.secretKey),
  maileva: createMailevaClient(config.maileva),
  store,
});

app.listen(config.port, () => {
  console.log(`Serveur démarré sur le port ${config.port} (Maileva : ${config.maileva.env})`);
});
