# Lucy

Lucy یک رابط GitHub Pages برای جست‌وجوی iTunes است. پس از انتخاب ترک، Cloudflare Worker درخواست را با GitHub App به workflow می‌فرستد؛ workflow در GitHub Actions با `yt-dlp` در YouTube Music جست‌وجو می‌کند، نتیجه‌ها را بر اساس عنوان/هنرمند/آلبوم/مدت رتبه‌بندی می‌کند، فقط کانال‌های allowlist‌شده را می‌پذیرد، MP3 را می‌سازد و metadata/کاور iTunes را ثبت می‌کند. لینک Release پس از تکمیل در صفحه نمایش داده می‌شود.

## معماری

- `site/`: رابط استاتیک و جست‌وجوی iTunes؛ هیچ secret خصوصی در آن قرار نمی‌گیرد.
- `worker/`: API درخواست/وضعیت، اعتبارسنجی Turnstile، rate limit، GitHub App token و KV.
- `.github/workflows/process-authorized-audio.yml`: اجرای محدودشدهٔ `yt-dlp` و انتشار Release asset.
- `scripts/process_audio.py`: جست‌وجو، enrichment با cookie، امتیازدهی، دانلود، تگ‌گذاری و کاور.

## راه‌اندازی Cloudflare

1. در Cloudflare یک Turnstile widget بساز و hostname سایت Pages را به آن اضافه کن؛ برای Pages پیش‌فرض این ریپو `sinlavi.github.io` است.
2. در ترمینال وارد `worker/` شو، dependencyهای قفل‌شده را نصب کن و KV namespace بساز:

   ```sh
   npm ci
   npx wrangler kv namespace create LUCY_JOBS
   ```

3. مقدار `id` خروجی را جایگزین placeholder در `worker/wrangler.toml` کن. در همان فایل `GITHUB_APP_ID` و `GITHUB_INSTALLATION_ID` را هم تنظیم کن. مقدار `ALLOWED_ORIGIN` باید origin دقیق Pages باشد، بدون path؛ `TURNSTILE_HOSTNAME` باید hostname دقیق باشد.
4. GitHub App بساز، آن را فقط روی ریپوی `sinlavi/lucy` نصب کن و permission آن را روی **Actions: Read and write** بگذار. Worker برای dispatch از installation token کوتاه‌عمر استفاده می‌کند.
5. در `worker/`، secretهای Worker را ثبت و deploy کن:

   ```sh
   npx wrangler secret put GITHUB_PRIVATE_KEY
   npx wrangler secret put TURNSTILE_SECRET_KEY
   npx wrangler secret put CALLBACK_TOKEN
   npx wrangler deploy
   ```

   `GITHUB_PRIVATE_KEY` همان PEM کلید private GitHub App است. برای `CALLBACK_TOKEN` یک مقدار تصادفی و بلند بساز؛ همان مقدار را در مرحلهٔ بعد به GitHub بده.

## راه‌اندازی GitHub

در **Settings → Secrets and variables → Actions** این موارد را بگذار:

- Secret `YOUTUBE_COOKIES`: فایل `cookies.txt` با فرمت Netscape از حسابی که مجاز به استفاده از آن هستی. Cookie فقط به runner داده می‌شود و بعد از دانلود پاک می‌شود؛ آن را در git، Pages یا Worker variable عمومی قرار نده و پس از انقضا/ابطال در Secret جایگزین کن.
- Secret `LUCY_CALLBACK_TOKEN`: همان مقدار `CALLBACK_TOKEN` که در Cloudflare ثبت شد.
- Variable `LUCY_WORKER_URL`: آدرس Worker، مانند `https://lucy-api.<account>.workers.dev`، بدون اسلش انتهایی.
- Variable `YOUTUBE_ALLOWED_CHANNEL_IDS`: شناسهٔ کانال‌های YouTube که مجاز به استفاده از محتوای آن‌ها هستی، جداشده با کاما. شناسه باید از نوع `UC...` باشد.

Workflow به `contents: write` برای ساخت Release نیاز دارد. Branch پیش‌فرض ریپو باید `main` باشد و workflow روی همان branch فعال باشد.

## اتصال Pages

در `site/config.js` مقدار `apiBaseUrl` را با URL واقعی Worker و `turnstileSiteKey` را با **site key عمومی** Turnstile جایگزین کن. این دو مقدار secret نیستند. سپس در **Settings → Pages** منبع را روی **GitHub Actions** بگذار و به `main` پوش کن.

کاربر باید در dialog کیفیت را انتخاب، Turnstile را کامل و تأیید کند که حق لازم را دارد. خروجی در GitHub Release عمومی است؛ فقط محتوایی را پردازش/منتشر کن که مجوزهای لازم برای استفاده و توزیع آن را داری. محدودیت نرخ Worker سه درخواست در دقیقه برای هر IP است.

## تست محلی

```sh
python -m unittest discover -s tests -v
cd worker
npm test
npx wrangler deploy --dry-run
```

پیش‌نمایش UI را می‌توان با `python3 -m http.server 8000 --directory site` اجرا کرد. تست‌های محلی به شبکهٔ YouTube یا cookie نیاز ندارند؛ اجرای end-to-end باید پس از پیکربندی Cloudflare bindings/secrets و GitHub App/Actions secrets انجام شود.