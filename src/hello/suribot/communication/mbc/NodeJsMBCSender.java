package hello.suribot.communication.mbc;

import org.json.JSONObject;

import hello.suribot.interfaces.IHttpSender;

/**
 * Classe controleur permettant d'envoyer des messages au programme Node.js de communication à MBC
 */
public class NodeJsMBCSender implements IHttpSender{
	
	public void sendMessage(JSONObject json, String message){
		System.out.println("NodeJsMBCSender sendMessage");
		// TODO : obtenir url du programme Node.js par getenv();
		try {
			json.put("text", message);
			sendPost("http://localhost:12344/mbc", json);
		} catch (Exception e) {
			System.out.println("NodeJsMBCSender : Message "+message+" not send... ");
			e.printStackTrace();
		}
	}

}