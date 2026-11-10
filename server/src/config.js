// Configuration lue depuis les variables d'environnement (voir .env.example).

function required(env, name) {
  const v = env[name];
  if (!v) throw new Error(`Variable d'environnement manquante : ${name}`);
  return v;
}

export function loadConfig(env = process.env) {
  const mailevaEnv = env.MAILEVA_ENV === 'production' ? 'production' : 'sandbox';
  const apiToken = required(env, 'APP_API_TOKEN');
  if (apiToken.length < 32) throw new Error('APP_API_TOKEN doit faire au moins 32 caractères');

  return {
    port: Number(env.PORT || 8080),
    dataDir: env.DATA_DIR || './data',
    apiToken,
    stripe: {
      secretKey: required(env, 'STRIPE_SECRET_KEY'),
      publishableKey: required(env, 'STRIPE_PUBLISHABLE_KEY'),
      webhookSecret: required(env, 'STRIPE_WEBHOOK_SECRET'),
    },
    pricing: {
      // Prix facturé par lettre, calculé côté serveur uniquement.
      baseCents: Number(env.PRICE_BASE_CENTS || 250),
      perPageCents: Number(env.PRICE_PER_PAGE_CENTS || 25),
      maxPages: Number(env.MAX_PAGES || 40),
    },
    maileva: {
      env: mailevaEnv,
      authUrl: env.MAILEVA_AUTH_URL || (mailevaEnv === 'production'
        ? 'https://connexion.maileva.com/auth/realms/services/protocol/openid-connect/token'
        : 'https://connexion.sandbox.maileva.net/auth/realms/services/protocol/openid-connect/token'),
      apiUrl: env.MAILEVA_API_URL || (mailevaEnv === 'production'
        ? 'https://api.maileva.com/mail/v2'
        : 'https://api.sandbox.maileva.net/mail/v2'),
      clientId: required(env, 'MAILEVA_CLIENT_ID'),
      clientSecret: required(env, 'MAILEVA_CLIENT_SECRET'),
      username: required(env, 'MAILEVA_USERNAME'),
      password: required(env, 'MAILEVA_PASSWORD'),
      // Affranchissement économique (équivalent « tarif lent » remboursé par les chaînes).
      postageType: env.MAILEVA_POSTAGE_TYPE || 'ECONOMIC',
    },
  };
}
