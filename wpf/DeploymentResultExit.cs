using System;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  internal Func<bool> DeploymentDiscardForTests;
  bool PermitDeploymentResultExit(){
   if(!deploymentResultUnsaved)return true;
   bool discard=DeploymentDiscardForTests!=null?DeploymentDiscardForTests():ConfirmWithLabel("Результат автодеплоя не сохранён на этом компьютере. Вернитесь и повторите сохранение или скопируйте реквизиты.\n\nВсё равно закрыть программу и потерять несохранённые реквизиты?","Несохранённый результат","Закрыть без сохранения");
   if(discard)return true;closeRequested=false;forceExit=false;return false;
  }
 }
}
