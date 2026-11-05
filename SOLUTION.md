# Yechim tavsifi (forma uchun)

Birja bitimlari bo‘yicha rus va o‘zbek tillarida (lotin va kirill) javob beradigan veb-servis. Har bir javob yonida SQL so‘rov va natija jadvali ko‘rinadi, javobdagi raqam ustiga olib borilsa yoki telefonda bosilsa, uning jadvaldagi katagi yonadi. So‘rov natijalari agent ishlayotgan paytdayoq ko‘rinadi, matnli javob esa tekshiruvdan keyin chiqadi.

**Texnologiyalar:** Java 25, Spring Boot 4.1, Spring AI 2.0, PostgreSQL 18 (docker compose), DeepSeek (OpenAI-mos API), Cloudflare Tunnel.

**Usul.** CSV fayllar PostgreSQL ga yuklanadi va tozalanadi: 230 xil yozilgan tovar nomi 41 ta tovarga birlashtirilgan, nav, sinf va marka bir xil ko‘rinishga keltirilgan, regionlar birlashtirilgan. README qoidalari tayyor ustunlarga aylantirilgan: so‘mdagi summa (bitim sanasidagi kurs bo‘yicha), hajm = miqdor × lot, bekor qilingan bitimlar belgisi, kechikishlar. LLM savolni tushunadi, kompaniya qidiruvi va SQL vositalarini chaqiradi hamda natijani so‘z bilan ifodalaydi. Raqamlarni model emas, ma’lumotlar bazasi hisoblaydi.

**O‘ylab topishdan himoya.**
1. SQL tekshiruvi: faqat bitta SELECT va faqat ruxsat etilgan jadvallar. Ma’lumotlar bazasi foydalanuvchisining o‘zi faqat o‘qish huquqiga ega.
2. Javobni kod tekshiradi: har bir raqam, sana va nom so‘rov natijalarida bo‘lishi shart. Tashqi manbaga havola rad etiladi. Tekshiruvdan o‘tmagan javob bir marta tuzatishga qaytariladi, yana o‘tmasa, matn o‘rniga so‘rov natijasining o‘zi ko‘rsatiladi.
3. Ma’lumotda javob bo‘lmasa, agent shuni ochiq aytadi: bunday javobda savolda aytilmagan raqam, kompaniya yoki tovar nomi bo‘lishi mumkin emas, buni kod tekshiradi. Faqat ma’lumot qamrovi sanalarini ko‘rsatish mumkin.
4. Javobda nomlar va kodlar so‘rov natijasidagidek, tarjimasiz ko‘rsatiladi. Manba CSV fayllar o‘zgartirilmaydi.
5. Foydalanuvchi va chatlar alohida sxemada saqlanadi. Agent SQL’i ishlaydigan faqat o‘qish huquqli rol bu sxemani ko‘rmaydi, shuning uchun savol orqali boshqa birovning chatini o‘qib bo‘lmaydi. Kirish ro‘yxatdan o‘tishsiz ishlaydi, foydalanuvchi nomi va parol bilan hisob esa ixtiyoriy (parollar BCrypt bilan saqlanadi). Mehmon chatlari sahifa yangilanganda o‘chadi, hisobdagi chatlar saqlanadi va ularni qayta nomlash yoki o‘chirish mumkin.

**Javoblar qanday tekshirildi.**
- 62 ta o‘z savolimiz (31 ta savol, ikki tilda). Kutilgan javoblar CSV fayllardan alohida kod bilan hisoblangan. Natija: 62/62.
- Ilova kodini ko‘rmasdan yozilgan 40 ta savoldan iborat ko‘r imtihon. Birinchi o‘tkazishda 39/40. Xatolar tuzatilgach qayta o‘tkazildi: 40/40 (bu savollar endi ko‘r emas, shuning uchun asosiy ko‘rsatkich birinchi natijadir).
- Javobi yo‘q 26 ta holatning barchasida (10 tasi ko‘r imtihonda, 16 tasi o‘z savollarimizda) agent hech narsa o‘ylab topmadi.
- Javoblar qo‘lda ham o‘qib chiqildi, shu tarzda avtomatik baholash ko‘rmaydigan xatolar topildi va tuzatildi.
- Ishlaydigan serverdagi oxirgi sinovda ikkala to‘plam ham o‘tdi (62/62 va 40/40), eng sekin javob 6,1 soniya (cheklov 90 soniya). Bundan oldingi sinovda LLM javobi kechikkanda bitta savol 75 soniyalik muddatga ulgurmadi: agent raqam o‘ylab topmadi va qayta urinishni so‘radi. Shundan keyin javob bermayotgan LLM so‘rovi 15 soniyada qayta yuboriladigan qilindi.

---

# Описание решения (для формы)

Веб-сервис, который отвечает на вопросы о биржевых сделках на русском и узбекском (латиница и кириллица). Рядом с каждым ответом видны SQL-запрос и таблица результата, а при наведении на число в ответе или нажатии на него в телефоне подсвечивается его ячейка. Результаты запросов видны уже во время работы агента, а текст ответа появляется после проверки.

**Технологии:** Java 25, Spring Boot 4.1, Spring AI 2.0, PostgreSQL 18 (docker compose), DeepSeek (OpenAI-совместимый API), Cloudflare Tunnel.

**Подход.** CSV загружаются в PostgreSQL и очищаются: 230 вариантов написания товаров сведены к 41 товару, сорта, классы и марки приведены к одному виду, названия регионов объединены. Правила из README стали готовыми колонками: сумма в сумах по курсу на дату сделки, объём = количество × лот, признак аннулирования, просрочки. LLM понимает вопрос, вызывает поиск компаний и SQL и формулирует ответ. Числа считает база данных, а не модель.

**Защита от выдумок.**
1. Проверка SQL: один SELECT и только разрешённые таблицы. Пользователь базы сам по себе имеет права только на чтение.
2. Ответ проверяет код: каждое число, дата и название должны быть в результатах запросов. Ссылки на внешние источники отклоняются. Непрошедший ответ один раз возвращается на исправление, а если не прошёл снова, вместо текста показывается сам результат запроса.
3. Если в данных нет ответа, агент говорит об этом прямо: в таком ответе не может быть чисел, компаний или товаров, которых нет в вопросе, и это проверяет код. Можно указать только даты, которые охватывают данные.
4. Названия и коды в ответе приводятся так, как их вернул запрос, без перевода. Исходные CSV не изменяются.
5. Пользователи и чаты хранятся в отдельной схеме. Роль только для чтения, под которой работает SQL агента, её не видит, поэтому прочитать чужой чат через вопрос нельзя. Сервис работает без регистрации, а аккаунт с именем и паролем необязателен (пароли хранятся через BCrypt). Чаты гостя удаляются при обновлении страницы, чаты в аккаунте сохраняются, и их можно переименовать или удалить.

**Как проверялись ответы.**
- 62 своих вопроса (31 вопрос на двух языках). Ожидаемые ответы посчитаны отдельным кодом прямо из CSV. Результат: 62/62.
- Слепой экзамен из 40 вопросов, написанных без доступа к коду приложения. Первый прогон: 39/40. После исправления ошибок повторный прогон: 40/40 (эти вопросы уже не слепые, поэтому честный показатель — первый результат).
- Во всех 26 случаях без ответа в данных (10 в слепом экзамене, 16 в своих вопросах) агент ничего не выдумал.
- Ответы также прочитаны вручную, что позволило найти и исправить ошибки, которые автоматическая оценка не видит.
- В последнем прогоне на рабочем сервере прошли оба набора (62/62 и 40/40), самый медленный ответ: 6,1 секунды (лимит 90 секунд). В предыдущем прогоне, когда ответ LLM задержался, один вопрос не уложился в срок 75 секунд: агент не выдумал чисел и попросил повторить вопрос. После этого неотвечающий запрос к LLM повторяется через 15 секунд.
