import { api, postJson } from "./api.js";
import { $ } from "./dom.js";
import { savedLanguage, saveLanguage } from "./language.js";

const TEXT = {
  ru: {
    brand: "Реестр сделок биржи",
    username: "Имя пользователя",
    usernameHint: "От 3 до 32 символов: латинские буквы, цифры, точка, дефис и подчёркивание.",
    password: "Пароль",
    passwordHint: "Не короче 8 символов.",
    guest: "Продолжить без входа",
    login: { heading: "Вход", submit: "Войти", switchText: "Нет аккаунта?", switchLink: "Зарегистрироваться" },
    register: { heading: "Регистрация", submit: "Создать аккаунт", switchText: "Уже есть аккаунт?", switchLink: "Войти" },
    errors: {
      invalid_username: "Имя пользователя: от 3 до 32 символов, латинские буквы, цифры, точка, дефис и подчёркивание.",
      weak_password: "Пароль должен быть не короче 8 символов и не длиннее 72 байт.",
      username_taken: "Это имя уже занято. Выберите другое.",
      invalid_credentials: "Неверное имя пользователя или пароль.",
      too_many_attempts: "Слишком много попыток. Подождите несколько минут и попробуйте снова.",
      unknown: "Не удалось выполнить запрос. Проверьте соединение и попробуйте снова."
    }
  },
  uz: {
    brand: "Birja bitimlari reyestri",
    username: "Foydalanuvchi nomi",
    usernameHint: "3 tadan 32 tagacha belgi: lotin harflari, raqamlar, nuqta, chiziqcha va pastki chiziq.",
    password: "Parol",
    passwordHint: "Kamida 8 ta belgi.",
    guest: "Kirmasdan davom etish",
    login: { heading: "Kirish", submit: "Kirish", switchText: "Hisobingiz yo‘qmi?", switchLink: "Ro‘yxatdan o‘tish" },
    register: { heading: "Ro‘yxatdan o‘tish", submit: "Hisob yaratish", switchText: "Hisobingiz bormi?", switchLink: "Kirish" },
    errors: {
      invalid_username: "Foydalanuvchi nomi: 3 tadan 32 tagacha belgi, lotin harflari, raqamlar, nuqta, chiziqcha va pastki chiziq.",
      weak_password: "Parol kamida 8 ta belgidan iborat va 72 baytdan oshmasligi kerak.",
      username_taken: "Bu nom band. Boshqasini tanlang.",
      invalid_credentials: "Foydalanuvchi nomi yoki parol noto‘g‘ri.",
      too_many_attempts: "Urinishlar juda ko‘p. Bir necha daqiqadan keyin qayta urinib ko‘ring.",
      unknown: "So‘rov bajarilmadi. Aloqani tekshiring va qayta urinib ko‘ring."
    }
  }
};

let lang = savedLanguage();
let mode = new URLSearchParams(location.search).get("mode") === "register" ? "register" : "login";
let errorCode = null;

function render() {
  saveLanguage(lang);
  document.documentElement.lang = lang;
  const t = TEXT[lang];
  const m = t[mode];
  document.querySelectorAll("[data-t]").forEach(node => {
    node.textContent = t[node.dataset.t];
  });
  document.querySelectorAll(".langs button").forEach(button => {
    button.setAttribute("aria-pressed", String(button.dataset.lang === lang));
  });
  $("heading").textContent = m.heading;
  $("submit").textContent = m.submit;
  $("switch-text").textContent = m.switchText;
  $("switch-link").textContent = m.switchLink;
  const registering = mode === "register";
  $("password").autocomplete = registering ? "new-password" : "current-password";
  $("username-hint").hidden = !registering;
  $("password-hint").hidden = !registering;
  $("error").hidden = !errorCode;
  $("error").textContent = errorCode ? (t.errors[errorCode] || t.errors.unknown) : "";
}

$("switch-link").addEventListener("click", event => {
  event.preventDefault();
  mode = mode === "login" ? "register" : "login";
  errorCode = null;
  history.replaceState(null, "", `/account?mode=${mode}`);
  render();
});

document.querySelectorAll(".langs button").forEach(button => {
  button.addEventListener("click", () => {
    lang = button.dataset.lang;
    render();
  });
});

$("form").addEventListener("submit", async event => {
  event.preventDefault();
  $("submit").disabled = true;
  errorCode = null;
  try {
    await postJson(`/api/auth/${mode}`, { username: $("username").value, password: $("password").value });
    location.assign("/chat");
  } catch (error) {
    errorCode = error.code || "unknown";
    $("password").value = "";
    render();
    $("password").focus();
  } finally {
    $("submit").disabled = false;
  }
});

api("/api/me").then(me => {
  if (me.username) location.replace("/chat");
}).catch(() => {});
render();
