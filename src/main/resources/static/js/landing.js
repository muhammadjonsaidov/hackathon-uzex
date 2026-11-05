import { $, el } from "./dom.js";
import { savedLanguage, saveLanguage } from "./language.js";

const PENDING_QUESTION_KEY = "uzex-pending-question";
const PART_DELAY_MS = 750;

/* The demo shows a real answer and query from the agent, with the figures it returned. */
const TEXT = {
  ru: {
    brand: "Реестр сделок биржи",
    signIn: "Войти",
    headline: "Спросите реестр. Ответ придёт с доказательством.",
    lead: "Агент отвечает на русском и узбекском о 500 000 сделок за 2022–2026 годы. Он берёт факты только из данных, показывает запрос с таблицей и прямо говорит, если ответа нет.",
    askLabel: "Ваш вопрос о сделках",
    askPlaceholder: "Например: кто купил больше всего риса в 2025 году?",
    start: "Спросить",
    openChat: "Открыть чат",
    tryQuestions: [
      "Сколько бензина продано в 2026 году?",
      "Какие были сделки по золоту?",
      "Кто пять крупнейших покупателей хлопка-сырца в 2024 году?"
    ],
    noSignup: "Регистрация не нужна",
    demoTitle: "Настоящий ответ агента",
    demoQuestion: "Сколько сделок было в 2025 году и сколько из них аннулировано?",
    answer: ["В 2025 году было зарегистрировано ", " сделки, из них аннулировано ", "."],
    demoCheck: "✓ Все числа найдены в результате запроса",
    demoHint: "Наведите на число, чтобы увидеть его ячейку.",
    replay: "Показать ещё раз",
    stepsTitle: "Как получается ответ",
    step1Title: "Модель пишет запрос",
    step1: "Языковая модель понимает вопрос и составляет SQL. Менять данные она не может: подключение только для чтения.",
    step2Title: "База считает числа",
    step2: "Суммы, объёмы и доли считает база, а не модель. Правила реестра уже учтены: валюта, лоты и аннулированные сделки.",
    step3Title: "Код сверяет ответ",
    step3: "Каждое число, дата и название проверяются по результатам запроса. Если что-то не сходится, текст модели не показывается.",
    refusalTitle: "Когда ответа нет, агент так и говорит",
    refusal: "Он не подбирает похожую компанию и не называет примерное число. Такой ответ тоже проверяет код: в нём не может быть чисел и других товаров.",
    refusalQuestion: "Какие были сделки по золоту?",
    refusalAnswer: "Данных о сделках по золоту нет: такого товара нет в каталоге биржи.",
    data: "В данных 500 000 сделок, 6 616 контрактов, 14 392 компании и 120 брокеров, со 3 января 2022 по 30 октября 2026 года.",
    footer: "Прототип для UZEX AI Hackathon 2026, не официальный сайт биржи. Все компании, ИНН, суммы и цены в данных вымышлены организаторами."
  },
  uz: {
    brand: "Birja bitimlari reyestri",
    signIn: "Kirish",
    headline: "Reyestrga savol bering. Javob dalili bilan keladi.",
    lead: "Agent 2022–2026-yillardagi 500 000 ta bitim haqida rus va o‘zbek tillarida javob beradi. U faktlarni faqat ma’lumotlardan oladi, so‘rov va jadvalni ko‘rsatadi, javob bo‘lmasa, shuni ochiq aytadi.",
    askLabel: "Bitimlar haqida savolingiz",
    askPlaceholder: "Masalan: 2025-yilda eng ko‘p guruchni kim sotib olgan?",
    start: "So‘rash",
    openChat: "Chatni ochish",
    tryQuestions: [
      "2026-yilda qancha benzin sotilgan?",
      "Oltin bo‘yicha qanday bitimlar bo‘lgan?",
      "2024-yilda paxta xomashyosining eng yirik 5 ta xaridori kim?"
    ],
    noSignup: "Ro‘yxatdan o‘tish shart emas",
    demoTitle: "Agentning haqiqiy javobi",
    demoQuestion: "2025-yilda nechta bitim bo‘lgan va ulardan nechtasi bekor qilingan?",
    answer: ["2025-yilda jami ", " ta bitim ro‘yxatga olingan, ulardan ", " tasi bekor qilingan."],
    demoCheck: "✓ Barcha raqamlar so‘rov natijasida topildi",
    demoHint: "Katagini ko‘rish uchun raqam ustiga olib boring.",
    replay: "Yana ko‘rsatish",
    stepsTitle: "Javob qanday tayyorlanadi",
    step1Title: "Model so‘rov yozadi",
    step1: "Til modeli savolni tushunadi va SQL tuzadi. U ma’lumotlarni o‘zgartira olmaydi: ulanish faqat o‘qish uchun.",
    step2Title: "Baza raqamlarni hisoblaydi",
    step2: "Summa, hajm va ulushlarni model emas, baza hisoblaydi. Reyestr qoidalari allaqachon hisobga olingan: valyuta, lotlar va bekor qilingan bitimlar.",
    step3Title: "Kod javobni tekshiradi",
    step3: "Har bir raqam, sana va nom so‘rov natijalari bilan solishtiriladi. Biror narsa to‘g‘ri kelmasa, model matni ko‘rsatilmaydi.",
    refusalTitle: "Javob bo‘lmasa, agent shuni aytadi",
    refusal: "U o‘xshash kompaniyani tanlamaydi va taxminiy raqam aytmaydi. Bunday javobni ham kod tekshiradi: unda raqam va boshqa tovar nomi bo‘lishi mumkin emas.",
    refusalQuestion: "Oltin bo‘yicha qanday bitimlar bo‘lgan?",
    refusalAnswer: "Oltin bo‘yicha ma’lumot yo‘q: mahsulotlar katalogida bunday mahsulot mavjud emas.",
    data: "Ma’lumotlarda 2022-yil 3-yanvardan 2026-yil 30-oktabrgacha 500 000 ta bitim, 6 616 ta shartnoma, 14 392 ta kompaniya va 120 ta broker bor.",
    footer: "UZEX AI Hackathon 2026 uchun prototip, birjaning rasmiy sayti emas. Ma’lumotlardagi barcha kompaniyalar, STIR, summa va narxlarni tashkilotchilar o‘ylab topgan."
  }
};

let lang = savedLanguage();
let timers = [];

function figure(text, cellId) {
  const button = el("button", "fig", text);
  button.type = "button";
  const cell = $(cellId);
  const light = on => cell.classList.toggle("is-source", on);
  button.addEventListener("mouseenter", () => light(true));
  button.addEventListener("mouseleave", () => light(false));
  button.addEventListener("focus", () => light(true));
  button.addEventListener("blur", () => light(false));
  return button;
}

/* Hands the question to the chat page, which asks it as soon as it opens. */
function goAsk(question) {
  try {
    if (question) sessionStorage.setItem(PENDING_QUESTION_KEY, question);
  } catch (e) { /* storage may be blocked: the chat still opens, empty */ }
  location.assign("/chat");
}

function renderTryQuestions(t) {
  $("try").replaceChildren(...t.tryQuestions.map(question => {
    const li = el("li");
    const button = el("button", "", question);
    button.type = "button";
    button.addEventListener("click", () => goAsk(question));
    li.append(button);
    return li;
  }));
}

function render() {
  saveLanguage(lang);
  document.documentElement.lang = lang;
  const t = TEXT[lang];
  document.querySelectorAll("[data-t]").forEach(node => {
    node.textContent = t[node.dataset.t];
  });
  document.querySelectorAll(".langs button").forEach(button => {
    button.setAttribute("aria-pressed", String(button.dataset.lang === lang));
  });
  $("ask-input").placeholder = t.askPlaceholder;
  $("closing-link").textContent = t.openChat;
  renderTryQuestions(t);
  $("demo-answer").replaceChildren(
    t.answer[0], figure("115 763", "cell-all"), t.answer[1], figure("8 085", "cell-annulled"), t.answer[2]);
}

/* The page's one moment of motion: the same order the real agent works in. */
function playDemo() {
  timers.forEach(clearTimeout);
  timers = [];
  const parts = [...document.querySelectorAll(".demo-part")];
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    parts.forEach(part => part.classList.remove("is-waiting"));
    return;
  }
  parts.forEach(part => part.classList.add("is-waiting"));
  $("demo-answer").classList.remove("is-playing");
  parts.forEach(part => {
    timers.push(setTimeout(() => {
      part.classList.remove("is-waiting");
      if (part.id === "demo-answer") part.classList.add("is-playing");
    }, Number(part.dataset.part) * PART_DELAY_MS));
  });
}

document.querySelectorAll(".langs button").forEach(button => {
  button.addEventListener("click", () => {
    lang = button.dataset.lang;
    render();
  });
});
$("ask-box").addEventListener("submit", event => {
  event.preventDefault();
  goAsk($("ask-input").value.trim());
});
$("replay").addEventListener("click", playDemo);
render();
playDemo();
