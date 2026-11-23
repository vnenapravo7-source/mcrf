"""Bounded normal UI setup/restoration for the exact observed test settings."""
import argparse,json
import p049_device as d
def visible(label,desc=False):return any(n.get('content-desc' if desc else 'text')==label for n in d.nodes())
def scroll(up=False):
    d.ready();d.adb('shell','input','swipe','510','700' if up else '2180','510','2220' if up else '1450','400')
def find(label,desc=False,up=False):
    for i in range(5):
        if visible(label,desc):return
        scroll(up)
    raise AssertionError('Observed control not found: '+label)
def replace(old,new):
    find(old);d.tap(old,desc=False);d.ready();d.adb('shell','input','keyevent','123')
    d.adb('shell','input','keyevent',*(['67']*len(old)));d.adb('shell','input','text',new);d.adb('shell','input','keyevent','4')
def main():
    p=argparse.ArgumentParser();p.add_argument('action',choices=['prepare','restore']);a=p.parse_args();prepare=a.action=='prepare'
    f=d.text('shell','dumpsys','window');assert any(x in f for x in ['org.mcrf.client/','com.android.chrome/','com.sec.android.app.launcher/'])
    d.adb('shell','am','start','-n','org.mcrf.client/.MainActivity');d.ready(install=True)
    d.tap('Маршрутизация');find('Всё кроме РФ')
    rule=next(n for n in d.nodes() if n.get('text')=='Всё кроме РФ')
    assert rule.get('checked')==('true' if prepare else 'false')
    d.tap('Всё кроме РФ',desc=False)
    d.tap('Напрямую' if prepare else 'VPN / прокси',desc=False)
    d.tap('VPN / прокси' if prepare else 'Напрямую',desc=False)
    d.tap('Настройки');find('Открыть: VPN и прокси',desc=True);d.tap('Открыть: VPN и прокси')
    find('Список локальных сетей');d.tap('Список локальных сетей',desc=False)
    replace('1400' if prepare else '1500','1500' if prepare else '1400')
    replace('172.19.0.1/30' if prepare else '10.89.0.2/32','10.89.0.2/32' if prepare else '172.19.0.1/30')
    d.tap('Настройки');find('Открыть: Обновления и движки',desc=True);d.tap('Открыть: Обновления и движки')
    # First card is sing-box; second is Steer. Only switch via its observed button.
    target='Steer · Установлен' if prepare else 'sing-box · Установлен'
    if not visible(target):
        if prepare:scroll()
        else:scroll(up=True)
    find('Выбрать');d.tap('Выбрать',desc=False);d.tap('Выбрать',desc=False)
    result=d.summary(d.nodes())
    if prepare and any(x['text']=='Модель DNS Steer' for x in result) and any(x['text']=='Подтвердить' for x in result):
        print('NORMAL_STEER_DNS_CONSENT_DIALOG: inspect fresh human authorization before confirming');return
    assert any(x['text']==('Steer · Выбран' if prepare else 'sing-box · Выбран') for x in result),'Core selection rejected; inspect dialog, do not bypass'
    d.tap('Подключения · VPN');find('Подключить · Отключено',desc=True,up=True)
    print('STEER_PREPARED' if prepare else 'ORIGINAL_SETTINGS_RESTORED')
if __name__=='__main__':main()
