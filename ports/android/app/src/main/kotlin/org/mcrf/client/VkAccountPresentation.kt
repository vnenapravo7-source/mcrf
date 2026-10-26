package org.mcrf.client

object VkAccountPresentation {
    fun action(linked:Boolean)=if(linked)"Отвязать VK"else"Привязать VK"
}
