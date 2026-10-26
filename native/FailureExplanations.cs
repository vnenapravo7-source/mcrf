using System;
using System.Text.RegularExpressions;
namespace SplifyWin {
 public static class FailureExplanations {
  public static string FromException(Exception error){if(error==null)return "Не удалось выполнить операцию.";string detail=JournalStyle.Redact(error.Message);if(String.IsNullOrWhiteSpace(detail))return "Не удалось выполнить операцию.";return error is FormatException||error is ArgumentException?detail:UserMessage(detail);}
  public static string UserMessage(string detail){
   if(String.IsNullOrWhiteSpace(detail))return detail;
   if(Regex.IsMatch(detail,@"(user|пользователь).*(cancel|declin|refus|отмен|отказ)|verification.*cancel|провер(ка|ку).*(отмен|прерван)|captcha.*(cancel|отмен)",RegexOptions.IgnoreCase))return "Подтверждение отменено пользователем. Запустите подключение снова и завершите проверку.";
   if(Regex.IsMatch(detail,@"captcha|challenge",RegexOptions.IgnoreCase))return "Сервис запросил подтверждение пользователя. Завершите проверку в открывшемся окне.";
   if(Regex.IsMatch(detail,@"i/o timeout|timed out|тайм-аут|operation was canceled",RegexOptions.IgnoreCase))return "Сервер не ответил вовремя. Проверьте сеть, затем попробуйте другой сервер.";
   if(Regex.IsMatch(detail,@"connection refused|actively refused|WSAECONNREFUSED|connectex:.*refused|в соединении отказано|удал[её]нн(ый|ая|ое|ые)?.*(отклонил|отказал).*(соедин|подключ)",RegexOptions.IgnoreCase))return "Сервер отклонил соединение. Проверьте адрес и доступность сервера.";
   if(Regex.IsMatch(detail,@"Could not resolve|resolve host|no such host|DNS",RegexOptions.IgnoreCase))return "Не удалось определить адрес через DNS. Проверьте DNS и доступ к сети.";
   if(Regex.IsMatch(detail,@"TUN|adapter|интерфейс",RegexOptions.IgnoreCase))return "Не удалось запустить сетевой адаптер TUN. Проверьте права Windows и журнал.";
   if(Regex.IsMatch(detail,@"certificate|TLS|x509",RegexOptions.IgnoreCase))return "Не удалось проверить защищённое соединение TLS. Проверьте время Windows и настройки сервера.";
   if(Regex.IsMatch(detail,@"порт прокси:\s*1024",RegexOptions.IgnoreCase))return "Укажите номер порта от 1024 до 65535.";
   if(Regex.IsMatch(detail,@"порт[^.\r\n]{0,80}(?:занят|недоступен|не откры)|(?:не удалось|невозможно)[^.\r\n]{0,60}(?:открыть|прослушивать|привязать)[^.\r\n]{0,20}порт|port.*(open|listen|bind)|address already in use",RegexOptions.IgnoreCase))return "Локальный порт занят или недоступен. Выберите другой порт в настройках.";
   if(Regex.IsMatch(detail,@"в тексте не найдены серверы|не найдены поддерживаемые ссылки|подписка не содержит серверы",RegexOptions.IgnoreCase))return "Не удалось распознать подключение. Проверьте ссылку или содержимое файла.";
   if(Regex.IsMatch(detail,@"SSH[- ](?:ключ|key)[^.\r\n]*(?:не найден|not found)",RegexOptions.IgnoreCase))return detail;
   if(Regex.IsMatch(detail,@"движок|engine|not found|не найден",RegexOptions.IgnoreCase))return "Не найден необходимый движок. Откройте обновления компонентов и установите его.";
   return detail;
  }
 }
}
