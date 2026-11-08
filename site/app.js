document.documentElement.classList.add("js");

const basePath = document.body.dataset.base || "../";
const pageId = document.body.dataset.page || "home";
const githubUrl = "https://github.com/LittleSurvival/yamibo-app";
const discordUrl = "https://discord.gg/3nhKpxM7Hc";
const releaseUrl = "https://github.com/LittleSurvival/yamibo-app/releases";
const pageAliases = {
  post: "threadDetail",
  text: "threadReader",
  comic: "comicReader",
};
const pageKey = pageAliases[pageId] || pageId;

const routes = {
  home: "../../index.html",
  main: "main-screens.html",
  forum: "forum.html",
  threadDetail: "thread-detail.html",
  threadReader: "thread-reader.html",
  search: "search.html",
  profile: "profile.html",
  history: "history.html",
  favorites: "favorites.html",
  tagManga: "tag-manga.html",
  comicReader: "comic-reader.html",
  sign: "sign.html",
  settings: "settings.html",
  development: "development.html",
};

const homeRoutes = {
  home: "index.html",
  main: "site/pages/main-screens.html",
  forum: "site/pages/forum.html",
  threadDetail: "site/pages/thread-detail.html",
  threadReader: "site/pages/thread-reader.html",
  search: "site/pages/search.html",
  profile: "site/pages/profile.html",
  history: "site/pages/history.html",
  favorites: "site/pages/favorites.html",
  tagManga: "site/pages/tag-manga.html",
  comicReader: "site/pages/comic-reader.html",
  sign: "site/pages/sign.html",
  settings: "site/pages/settings.html",
  development: "site/pages/development.html",
};

const screenshots = {
  home: "assets/screenshots/home.png",
  forum: "assets/screenshots/forum-manage.png",
  threadDetail: "assets/screenshots/thread-detail.png",
  threadReader: "assets/screenshots/thread-reader.png",
  textTools: "assets/screenshots/text-tools.png",
  textContents: "assets/screenshots/text-contents.png",
  textSettings: "assets/screenshots/text-settings.png",
  search: "assets/screenshots/search.png",
  profile: "assets/screenshots/profile.png",
  history: "assets/screenshots/history.png",
  favorites: "assets/screenshots/favorite.png",
  message: "assets/screenshots/message.png",
  tagManga: "assets/screenshots/tag-manga-detail.png",
  comicReader: "assets/screenshots/image-reader.png",
  comicTools: "assets/screenshots/comic-tools.png",
  comicSettings: "assets/screenshots/comic-settings.png",
  sign: "assets/screenshots/sign-settings.png",
  settings: "assets/screenshots/settings.png",
};

const languages = [
  ["zh-Hant", "繁體中文"],
  ["zh-Hans", "简体中文"],
  ["en", "English"],
];

const i18n = {
  "zh-Hant": {
    brand: "Yamibo App 說明",
    menu: "目錄",
    download: "下載",
    app: "App",
    community: "社群",
    developmentGroup: "開發",
    ready: "GitHub Releases",
    readyText: "前往發布頁下載最新 APK 與查看版本資訊。",
    homeTitle: "Yamibo App",
    homeEyebrow: "產品說明",
    homeLead: "Yamibo App 讓論壇閱讀更接近行動閱讀器：快速找版塊、接續閱讀、整理收藏、追蹤消息，也能處理每日簽到。",
    homeCaption: "首頁可以快速進入常用版塊與搜尋。",
    appIntro: "這些功能讓手機閱讀、追蹤與整理內容更順手。",
    communityIntro: "加入社群或查看原始碼，回報問題、追蹤進度與參與討論。",
    communityLinks: {
      discord: "Discord 社群",
      github: "GitHub 專案",
    },
    devIntro: "給協助開發的人查看專案組成、使用技術與常用命令。",
    nav: {
      home: "總覽",
      main: "主畫面",
      forum: "版塊",
      threadDetail: "帖子詳情頁",
      threadReader: "閱讀器",
      search: "搜尋",
      profile: "我的資料",
      history: "閱讀歷史",
      favorites: "收藏頁",
      tagManga: "標籤漫畫模式",
      comicReader: "漫畫閱讀器",
      sign: "簽到",
      settings: "設定",
      development: "專案開發",
    },
    pages: {
      main: {
        eyebrow: "App 主畫面",
        title: "主畫面",
        lead: "底部五個入口把找內容、看紀錄、收消息、整理收藏與個人功能分開。",
        bullets: ["首頁負責找版塊與搜尋內容。", "紀錄會保存最近看過的位置。", "消息集中顯示收藏更新、私訊與提醒。", "收藏可以分類、排序與同步。", "我的頁放登入狀態、簽到與設定入口。"],
        shots: [
          ["home", "首頁：快速找版塊與進入搜尋。"],
          ["history", "紀錄：回到上次閱讀的位置。"],
          ["message", "消息：追蹤更新與提醒。"],
          ["favorites", "收藏：整理想持續追蹤的帖子。"],
          ["profile", "我的：帳號、簽到與設定入口。"],
        ],
      },
      forum: {
        eyebrow: "版塊",
        title: "版塊",
        lead: "版塊頁把公告、置頂與一般帖子放在同一個列表，適合快速掃過最新內容。",
        bullets: ["頂部顯示今日、主題數、排名與排序。", "公告與置頂用醒目標籤區分。", "一般帖子會顯示標題、摘要、觀看數與回覆數。"],
        shots: [["forum", "管理版範例：公告、置頂與帖子列表。"]],
      },
      threadDetail: {
        eyebrow: "文學區與子板塊專用",
        title: "帖子詳情頁",
        lead: "帖子詳情頁用來閱讀文學區與子板塊的一般帖子，並保留回覆、收藏、分享與進入閱讀器的入口。",
        bullets: ["標題、正文與回覆集中顯示。", "可以從這裡收藏、分享或繼續進入閱讀器。"],
        shots: [["threadDetail", "帖子詳情頁：正文與互動入口。"]],
      },
      threadReader: {
        eyebrow: "長文閱讀",
        title: "閱讀器",
        lead: "閱讀器讓長文更適合在手機上連續閱讀，並保留目錄、閱讀設定與進度。",
        bullets: ["目錄可以快速跳到指定段落。", "閱讀設定可調整字體、行距、主題與簡繁顯示。", "離開後會保留目前閱讀位置。"],
        shots: [
          ["threadReader", "閱讀畫面：減少干擾，專注正文。"],
          ["textTools", "控制列：目錄、設定、收藏、分享與回覆入口。"],
          ["textContents", "目錄：快速切換段落與位置。"],
          ["textSettings", "閱讀設定：調整字體、行距、簡繁與主題。"],
        ],
      },
      search: {
        eyebrow: "搜尋",
        title: "搜尋",
        lead: "搜尋頁可以用關鍵字找帖子，也能貼上帖子連結直接打開內容。",
        bullets: ["輸入關鍵字找帖子。", "貼上帖子連結即可打開內容。", "搜尋結果可直接進入詳情或閱讀畫面。"],
        shots: [["search", "搜尋：關鍵字與帖子連結都能使用。"]],
      },
      profile: {
        eyebrow: "個人入口",
        title: "我的資料",
        lead: "我的頁集中顯示登入狀態、簽到狀態、設定入口與閱讀統計。",
        bullets: ["顯示目前登入帳號與積分資訊。", "每日簽到狀態直接可見。", "設定與閱讀統計以大按鈕呈現。"],
        shots: [["profile", "我的：帳號、簽到與設定入口。"]],
      },
      history: {
        eyebrow: "接續閱讀",
        title: "閱讀歷史",
        lead: "閱讀歷史會記住最近看過的內容，讓你回到上次中斷的位置。",
        bullets: ["自動保存最近閱讀位置。", "從歷史紀錄快速回到上次中斷的地方。", "可搜尋、篩選或刪除不需要的紀錄。"],
        shots: [["history", "閱讀歷史：快速接續未讀完的內容。"]],
      },
      favorites: {
        eyebrow: "收藏管理",
        title: "收藏頁",
        lead: "收藏頁用來整理想追蹤的帖子，也能同步遠端收藏狀態。",
        bullets: ["用分類整理想追蹤的帖子。", "可搜尋、排序與批次管理收藏。", "遠端收藏同步會顯示目前進度。"],
        shots: [["favorites", "收藏頁：分類、搜尋與同步狀態。"]],
      },
      tagManga: {
        eyebrow: "App 專用瀏覽",
        title: "標籤漫畫模式",
        lead: "標籤漫畫模式把同一標籤下的漫畫內容集中起來，比網站列表更適合手機連續瀏覽。",
        bullets: ["把同一標籤下的漫畫內容集中瀏覽。", "比網站列表更適合在手機上連續翻看。", "可直接接續進入漫畫閱讀器。"],
        shots: [["tagManga", "標籤漫畫模式：依標籤集中瀏覽漫畫內容。"]],
      },
      comicReader: {
        eyebrow: "圖片閱讀",
        title: "漫畫閱讀器",
        lead: "漫畫閱讀器會連續顯示圖片內容，並提供翻頁方式、顯示設定與閱讀進度。",
        bullets: ["圖片會以閱讀器方式連續顯示。", "可調整閱讀方向與翻頁操作。", "離開後保留目前閱讀進度。"],
        shots: [
          ["comicReader", "漫畫閱讀器：連續瀏覽圖片內容。"],
          ["comicTools", "控制列：頁碼、翻頁、設定與分享入口。"],
          ["comicSettings", "閱讀設定：調整閱讀方向與輕觸區域。"],
        ],
      },
      sign: {
        eyebrow: "每日簽到",
        title: "簽到功能",
        lead: "簽到功能集中在我的頁與簽到設定頁，涵蓋提醒、操作模式與補簽偏好。",
        bullets: ["開屏提醒避免忘記每日簽到。", "半自動模式協助走完 App 內簽到步驟。", "全手動模式保留使用者自行操作。", "補簽可在驗證後自動嘗試。"],
        shots: [
          ["profile", "我的頁會顯示今日簽到狀態。"],
          ["sign", "簽到設定：提醒、操作模式與補簽。"],
        ],
      },
      settings: {
        eyebrow: "偏好管理",
        title: "設定",
        lead: "設定頁把外觀、語言、閱讀器、收藏、通知、快取、更新與簽到集中管理。",
        bullets: ["外觀與語言控制整體顯示。", "小說與漫畫閱讀器設定分開。", "通知、背景同步與更新集中處理。"],
        shots: [["settings", "設定：集中調整 App 使用偏好。"]],
      },
    },
    development: {
      eyebrow: "協助開發",
      title: "專案開發",
      lead: "這裡給協助開發的人快速了解專案組成、主要技術與常用命令。",
      sections: [
        ["專案組成", "composeApp 負責介面、導航與平台整合。shared 負責資料、網路、設定與資料庫。site 是 GitHub Pages 產品說明站。"],
        ["主要技術", "Kotlin Multiplatform、Compose Multiplatform、Ktor、SQLDelight、Coil、WorkManager、OpenCC 與 yamibo-api。"],
        ["支援平台", "目前以 Android App 為主要驗證目標，專案也保留跨平台共用資料層。"],
      ],
      commands: ["adb devices", "./gradlew.bat :composeApp:compileDebugKotlinAndroid --console=plain", "./gradlew.bat :composeApp:installDebug --console=plain"],
    },
  },
  "zh-Hans": {
    brand: "Yamibo App 说明",
    menu: "目录",
    download: "下载",
    app: "App",
    community: "社群",
    developmentGroup: "开发",
    ready: "GitHub Releases",
    readyText: "前往发布页下载最新 APK 与查看版本信息。",
    homeTitle: "Yamibo App",
    homeEyebrow: "产品说明",
    homeLead: "Yamibo App 让论坛阅读更接近移动阅读器：快速找版块、接续阅读、整理收藏、追踪消息，也能处理每日签到。",
    homeCaption: "首页可以快速进入常用版块与搜索。",
    appIntro: "这些功能让手机阅读、追踪与整理内容更顺手。",
    communityIntro: "加入社群或查看源码，回报问题、追踪进度与参与讨论。",
    communityLinks: {
      discord: "Discord 社群",
      github: "GitHub 项目",
    },
    devIntro: "给协助开发的人查看项目组成、使用技术与常用命令。",
    nav: {
      home: "总览", main: "主画面", forum: "版块", threadDetail: "帖子详情页", threadReader: "阅读器", search: "搜索", profile: "我的资料", history: "阅读历史", favorites: "收藏页", tagManga: "标签漫画模式", comicReader: "漫画阅读器", sign: "签到", settings: "设置", development: "项目开发",
    },
    pages: {},
    development: {},
  },
  en: {
    brand: "Yamibo App Guide",
    menu: "Menu",
    download: "Download",
    app: "App",
    community: "Community",
    developmentGroup: "Development",
    ready: "GitHub Releases",
    readyText: "Download the latest APK and view release notes from the releases page.",
    homeTitle: "Yamibo App",
    homeEyebrow: "Product guide",
    homeLead: "Yamibo App turns forum browsing into a mobile reading experience: find boards, resume reading, organize favorites, follow updates, and handle daily sign-in.",
    homeCaption: "Home opens common boards and search quickly.",
    appIntro: "These features make reading, tracking, and organizing content easier on mobile.",
    communityIntro: "Join the community or view the source code to report issues, follow progress, and discuss the app.",
    communityLinks: {
      discord: "Discord community",
      github: "GitHub repository",
    },
    devIntro: "For contributors who need the project structure, technology stack, and common commands.",
    nav: {
      home: "Overview", main: "Main screen", forum: "Boards", threadDetail: "Post details", threadReader: "Text reader", search: "Search", profile: "Profile", history: "Reading history", favorites: "Favorites", tagManga: "Tagged comics", comicReader: "Comic reader", sign: "Sign-in", settings: "Settings", development: "Development",
    },
    pages: {},
    development: {},
  },
};

const hansReplacements = [
  ["快取", "缓存"],
  ["連結", "链接"],
  ["資訊", "信息"],
  ["頁", "页"], ["閱讀", "阅读"], ["訊", "讯"], ["體", "体"], ["態", "态"], ["顯", "显"], ["設", "设"], ["專", "专"], ["塊", "块"], ["標", "标"], ["籤", "签"], ["簽", "签"], ["內", "内"], ["與", "与"], ["過", "过"], ["這", "这"], ["會", "会"], ["據", "据"], ["應", "应"], ["進", "进"], ["選", "选"], ["單", "单"], ["開", "开"], ["關", "关"], ["覽", "览"], ["圖", "图"], ["號", "号"], ["聯", "联"], ["統", "统"], ["遠", "远"], ["狀", "状"], ["離", "离"], ["後", "后"], ["動", "动"], ["補", "补"], ["嘗", "尝"], ["試", "试"], ["長", "长"], ["調", "调"], ["節", "节"], ["簡", "简"], ["適", "适"], ["優", "优"], ["續", "续"], ["蹤", "踪"], ["彙", "汇"],
  ["個", "个"], ["錄", "录"], ["紀", "纪"], ["責", "责"], ["負", "负"], ["尋", "寻"], ["類", "类"], ["帳", "账"], ["頂", "顶"], ["掃", "扫"], ["區", "区"], ["觀", "观"], ["數", "数"], ["覆", "复"], ["學", "学"], ["詳", "详"], ["並", "并"], ["從", "从"], ["繼", "继"], ["讓", "让"], ["機", "机"], ["連", "连"], ["減", "减"], ["擾", "扰"], ["鍵", "键"], ["貼", "贴"], ["結", "结"], ["計", "计"], ["積", "积"], ["見", "见"], ["歷", "历"], ["記", "记"], ["斷", "断"], ["篩", "筛"], ["刪", "删"], ["讀", "读"], ["瀏", "浏"], ["畫", "画"], ["網", "网"], ["碼", "码"], ["輕", "轻"], ["觸", "触"], ["協", "协"], ["驟", "骤"], ["驗", "验"], ["證", "证"], ["語", "语"], ["處", "处"], ["層", "层"], ["給", "给"], ["裡", "里"], ["為", "为"], ["來", "来"], ["題", "题"], ["範", "范"], ["蓋", "盖"], ["當", "当"], ["現", "现"], ["組", "组"], ["換", "换"], ["術", "术"], ["於", "于"], ["麼", "么"], ["門", "门"], ["電", "电"], ["書", "书"], ["寫", "写"], ["對", "对"], ["練", "练"], ["擊", "击"], ["獲", "获"], ["資", "资"], ["說", "说"], ["鈕", "钮"],
];
i18n["zh-Hans"].pages = mapPages(i18n["zh-Hant"].pages, hansReplacements);
i18n["zh-Hans"].development = mapValue(i18n["zh-Hant"].development, [
  ["協助開發", "协助开发"], ["專案開發", "项目开发"], ["這裡給協助開發的人快速了解專案組成、主要技術與常用命令。", "这里给协助开发的人快速了解项目组成、主要技术与常用命令。"], ["專案組成", "项目组成"], ["專案", "项目"], ["負責", "负责"], ["介面", "界面"], ["導航", "导航"], ["資料庫", "数据库"], ["資料", "数据"], ["網路", "网络"], ["設定", "设置"], ["產品說明站", "产品说明站"], ["主要技術", "主要技术"], ["支援平台", "支持平台"], ["目前以 Android App 為主要驗證目標，專案也保留跨平台共用資料層。", "目前以 Android App 为主要验证目标，项目也保留跨平台共用数据层。"],
  ...hansReplacements,
]);
i18n.en.pages = {
  main: {
    eyebrow: "App home",
    title: "Main screen",
    lead: "The five bottom tabs separate discovery, history, updates, favorites, and personal tools.",
    bullets: ["Home helps you find boards and search content.", "History remembers recent reading positions.", "Messages collect favorite updates, private messages, and alerts.", "Favorites can be grouped, sorted, and synced.", "Profile contains account, sign-in, and settings shortcuts."],
    shots: [["home", "Home: find boards and search."], ["history", "History: resume from the last position."], ["message", "Messages: follow updates and alerts."], ["favorites", "Favorites: organize tracked posts."], ["profile", "Profile: account, sign-in, and settings."]],
  },
  forum: {
    eyebrow: "Boards",
    title: "Boards",
    lead: "Board pages place announcements, pinned items, and regular posts in one list.",
    bullets: ["Stats and sorting are visible at the top.", "Announcements and pinned posts use clear labels.", "Post cards show title, preview, views, and replies."],
    shots: [["forum", "Board example: announcements, pinned posts, and regular posts."]],
  },
  threadDetail: {
    eyebrow: "For literature boards",
    title: "Post details",
    lead: "Post details are used by literature boards and sub-boards, with text, replies, favorite, share, and reader entry points.",
    bullets: ["Title, content, and replies are shown together.", "You can favorite, share, or continue in the reader."],
    shots: [["threadDetail", "Post details: content and actions."]],
  },
  threadReader: {
    eyebrow: "Long-form reading",
    title: "Text reader",
    lead: "The reader makes long posts easier to read on mobile and keeps table of contents, display settings, and progress.",
    bullets: ["The table of contents jumps to sections quickly.", "Display settings adjust font size, line height, theme, and Chinese conversion.", "Reading position is kept after leaving."],
    shots: [["threadReader", "Reading view: focus on text."], ["textTools", "Controls: contents, settings, favorite, share, and reply."], ["textContents", "Contents: jump between sections."], ["textSettings", "Reader settings: font, line height, conversion, and theme."]],
  },
  search: {
    eyebrow: "Search",
    title: "Search",
    lead: "Search by keyword or paste a post link to open content directly.",
    bullets: ["Enter keywords to find posts.", "Paste a post link to open it.", "Results can open details or the reading view."],
    shots: [["search", "Search: keywords and post links are both supported."]],
  },
  profile: {
    eyebrow: "Personal tools",
    title: "Profile",
    lead: "Profile shows account status, sign-in status, settings, and reading statistics.",
    bullets: ["Shows current account and points.", "Daily sign-in status is visible.", "Settings and reading statistics are large shortcuts."],
    shots: [["profile", "Profile: account, sign-in, and settings."]],
  },
  history: {
    eyebrow: "Resume reading",
    title: "Reading history",
    lead: "Reading history remembers recent content so you can resume where you stopped.",
    bullets: ["Recent positions are saved automatically.", "Resume quickly from the last interruption.", "Search, filter, or remove history items."],
    shots: [["history", "Reading history: resume unfinished content."]],
  },
  favorites: {
    eyebrow: "Favorite management",
    title: "Favorites",
    lead: "Favorites organize posts you want to follow and can sync remote favorite state.",
    bullets: ["Use categories to organize tracked posts.", "Search, sort, and batch manage favorites.", "Remote sync shows current progress."],
    shots: [["favorites", "Favorites: categories, search, and sync state."]],
  },
  tagManga: {
    eyebrow: "App-only browsing",
    title: "Tagged comics",
    lead: "Tagged comics collect comic posts under the same tag for easier mobile browsing.",
    bullets: ["Browse comic content under the same tag.", "More comfortable than the website list for continuous mobile viewing.", "Open directly into the comic reader."],
    shots: [["tagManga", "Tagged comics: browse comic content by tag."]],
  },
  comicReader: {
    eyebrow: "Image reading",
    title: "Comic reader",
    lead: "Comic reader displays image posts continuously with page controls, display settings, and progress.",
    bullets: ["Images are shown as a continuous reading view.", "Reading direction and page controls can be adjusted.", "Current progress is kept after leaving."],
    shots: [["comicReader", "Comic reader: continuous image reading."], ["comicTools", "Controls: page slider, page actions, settings, and share."], ["comicSettings", "Reader settings: reading direction and touch zones."]],
  },
  sign: {
    eyebrow: "Daily sign-in",
    title: "Sign-in",
    lead: "Sign-in is managed from Profile and Sign-in settings, with reminders, operation modes, and make-up preferences.",
    bullets: ["Launch reminders help avoid missing daily sign-in.", "Semi-auto mode helps complete the in-app sign-in steps.", "Manual mode keeps every action under user control.", "Make-up sign-in can retry after verification."],
    shots: [["profile", "Profile shows today's sign-in status."], ["sign", "Sign-in settings: reminders, operation mode, and make-up sign-in."]],
  },
  settings: {
    eyebrow: "Preferences",
    title: "Settings",
    lead: "Settings manage appearance, language, readers, favorites, notifications, cache, updates, and sign-in.",
    bullets: ["Appearance and language control global display.", "Text and comic reader settings are separate.", "Notifications, background sync, and updates are grouped together."],
    shots: [["settings", "Settings: manage app preferences in one place."]],
  },
};
i18n.en.development = {
  eyebrow: "Contributors",
  title: "Development",
  lead: "A quick overview of the project structure, main technologies, and common commands for contributors.",
  sections: [
    ["Project structure", "composeApp owns UI, navigation, and platform integration. shared owns data, network, settings, and database layers. site is the GitHub Pages product guide."],
    ["Main technologies", "Kotlin Multiplatform, Compose Multiplatform, Ktor, SQLDelight, Coil, WorkManager, OpenCC, and yamibo-api."],
    ["Target platform", "Android is the primary validation target. Shared data layers remain cross-platform."],
  ],
  commands: i18n["zh-Hant"].development.commands,
};

function mapPages(pages, replacements) {
  return Object.fromEntries(Object.entries(pages).map(([key, value]) => [key, mapValue(value, replacements)]));
}

function mapValue(value, replacements) {
  if (typeof value === "string") return replacements.reduce((text, [from, to]) => text.split(from).join(to), value);
  if (Array.isArray(value)) return value.map((item) => mapValue(item, replacements));
  if (value && typeof value === "object") return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, mapValue(item, replacements)]));
  return value;
}

function getLanguage() {
  const stored = localStorage.getItem("yamibo-site-language");
  return i18n[stored] ? stored : "zh-Hant";
}

function pageHref(key) {
  return pageId === "home" ? homeRoutes[key] : routes[key];
}

function assetPath(path) {
  return `${basePath}${path}`;
}

function renderHeader(text, lang) {
  document.querySelector(".site-header").innerHTML = `
    <a class="brand" href="${pageHref("home")}">
      <img src="${assetPath("assets/icons/logo_homepage.png")}" alt="" class="brand-logo">
      <span>${text.brand}</span>
    </a>
    <div class="header-actions">
      <select class="language-select" aria-label="${text.brand}">
        ${languages.map(([value, label]) => `<option value="${value}" ${value === lang ? "selected" : ""}>${label}</option>`).join("")}
      </select>
      ${pageId === "home" ? "" : `<button class="nav-toggle" type="button" aria-expanded="false">${text.menu}</button>`}
    </div>`;
  document.querySelector(".language-select").addEventListener("change", (event) => {
    localStorage.setItem("yamibo-site-language", event.target.value);
    render();
  });
}

function renderSidebar(text) {
  const appKeys = ["main", "forum", "threadDetail", "threadReader", "search", "profile", "history", "favorites", "tagManga", "comicReader", "sign", "settings"];
  return `<aside class="sidebar">
    <nav>
      <div class="side-group">
        <div class="side-title">${text.download}</div>
        <a href="${releaseUrl}">${text.ready}</a>
      </div>
      <div class="side-group">
        <div class="side-title">${text.community}</div>
        <a href="${discordUrl}">Discord</a>
        <a href="${githubUrl}">GitHub</a>
      </div>
      <div class="side-group">
        <div class="side-title">${text.app}</div>
        ${appKeys.map((key) => `<a class="${pageKey === key ? "is-active" : ""}" href="${pageHref(key)}">${text.nav[key]}</a>`).join("")}
      </div>
      <div class="side-group">
        <div class="side-title">${text.developmentGroup}</div>
        <a class="${pageKey === "development" ? "is-active" : ""}" href="${pageHref("development")}">${text.nav.development}</a>
      </div>
    </nav>
  </aside>`;
}

function renderHome(text) {
  const appKeys = ["main", "forum", "threadDetail", "threadReader", "search", "profile", "history", "favorites", "tagManga", "comicReader", "sign", "settings"];
  const preview = {
    main: "home",
    forum: "forum",
    threadDetail: "threadDetail",
    threadReader: "threadReader",
    search: "search",
    profile: "profile",
    history: "history",
    favorites: "favorites",
    tagManga: "tagManga",
    comicReader: "comicReader",
    sign: "sign",
    settings: "settings",
  };
  document.querySelector("#app").innerHTML = `
    <main class="home-shell">
      <section class="home-hero">
        <div>
          <p class="eyebrow">${text.homeEyebrow}</p>
          <h1>${text.homeTitle}</h1>
          <p class="lead">${text.homeLead}</p>
        </div>
        <figure class="hero-shot">
          <img src="${assetPath(screenshots.home)}" alt="">
          <figcaption>${text.homeCaption}</figcaption>
        </figure>
      </section>
      <section class="home-section" id="download">
        <div class="section-head"><h2>${text.download}</h2></div>
        <div class="empty-box">
          <strong>${text.ready}</strong>
          <p>${text.readyText}</p>
          <a class="release-link" href="${releaseUrl}">${releaseUrl}</a>
        </div>
      </section>
      <section class="home-section">
        <div class="section-head"><h2>${text.app}</h2><p>${text.appIntro}</p></div>
        <div class="overview-grid">
          ${appKeys.map((key) => {
            const page = text.pages[key];
            return `<a class="overview-card" href="${pageHref(key)}">
              <img src="${assetPath(screenshots[preview[key]])}" alt="">
              <span>${page.title}</span>
              <p>${page.lead}</p>
            </a>`;
          }).join("")}
        </div>
      </section>
      <section class="home-section" id="community">
        <div class="section-head"><h2>${text.community}</h2><p>${text.communityIntro}</p></div>
        <div class="community-grid">
          <a class="community-card" href="${discordUrl}">
            <strong>${text.communityLinks.discord}</strong>
            <span>${discordUrl}</span>
          </a>
          <a class="community-card" href="${githubUrl}">
            <strong>${text.communityLinks.github}</strong>
            <span>${githubUrl}</span>
          </a>
        </div>
      </section>
      <section class="home-section">
        <div class="section-head"><h2>${text.developmentGroup}</h2><p>${text.devIntro}</p></div>
        <div class="overview-grid">
          <a class="overview-card" href="${pageHref("development")}">
            <img src="${assetPath(screenshots.settings)}" alt="">
            <span>${text.nav.development}</span>
            <p>${text.development.lead}</p>
          </a>
        </div>
      </section>
    </main>`;
}

function renderFeaturePage(text) {
  const page = text.pages[pageKey];
  document.title = `${page.title} - ${text.brand}`;
  document.querySelector("#app").innerHTML = `
    <div class="page-shell">
      ${renderSidebar(text)}
      <main class="page-content">
        <section class="page-hero">
          <p class="eyebrow">${page.eyebrow}</p>
          <h1>${page.title}</h1>
          <p class="lead">${page.lead}</p>
        </section>
        <section class="content-grid">
          <div class="copy-card">
            <h2>${text.nav.home === "Overview" ? "Overview" : "概覽"}</h2>
            <ul class="summary-list">${page.bullets.map((item) => `<li>${item}</li>`).join("")}</ul>
          </div>
          <div class="shot-grid">
            ${page.shots.map(([shot, caption]) => `<figure class="shot"><img src="${assetPath(screenshots[shot])}" alt=""><figcaption>${caption}</figcaption></figure>`).join("")}
          </div>
        </section>
      </main>
    </div>`;
}

function renderDevelopment(text) {
  const page = text.development;
  document.title = `${page.title} - ${text.brand}`;
  document.querySelector("#app").innerHTML = `
    <div class="page-shell">
      ${renderSidebar(text)}
      <main class="page-content">
        <section class="page-hero">
          <p class="eyebrow">${page.eyebrow}</p>
          <h1>${page.title}</h1>
          <p class="lead">${page.lead}</p>
        </section>
        <section class="tech-grid">
          ${page.sections.map(([title, body]) => `<article class="tech-card"><h2>${title}</h2><p>${body}</p></article>`).join("")}
          <article class="tech-card">
            <h2>${text.nav.home === "Overview" ? "Common commands" : "常用命令"}</h2>
            <ul class="code-list">${page.commands.map((command) => `<li><code>${command}</code></li>`).join("")}</ul>
          </article>
        </section>
      </main>
    </div>`;
}

function activateUi() {
  const toggle = document.querySelector(".nav-toggle");
  const sidebar = document.querySelector(".sidebar");
  if (toggle && sidebar) {
    toggle.addEventListener("click", () => {
      const open = sidebar.classList.toggle("is-open");
      toggle.setAttribute("aria-expanded", String(open));
    });
  }

  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) entry.target.classList.add("is-visible");
    });
  }, { threshold: 0.1 });
  const revealItems = document.querySelectorAll(".overview-card,.shot,.copy-card,.tech-card,.empty-box");
  revealItems.forEach((el) => observer.observe(el));
  window.setTimeout(() => {
    revealItems.forEach((el) => el.classList.add("is-visible"));
  }, 120);
}

function render() {
  const lang = getLanguage();
  const text = i18n[lang];
  document.documentElement.lang = lang;
  renderHeader(text, lang);
  if (pageKey === "home") {
    document.title = text.brand;
    renderHome(text);
  } else if (pageKey === "development") {
    renderDevelopment(text);
  } else {
    renderFeaturePage(text);
  }
  activateUi();
}

render();
