package hello.suribot.analyze;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import hello.suribot.SuribotKeys;
import hello.suribot.analyze.contracts.ContractAnalyzer;
import hello.suribot.analyze.contracts.IContractAnalyzer;
import hello.suribot.analyze.jsonmemory.JSONMemory;
import hello.suribot.communication.api.APIController;
import hello.suribot.communication.api.ApiUrls;
import hello.suribot.communication.recastConnector.RecastBotConnectorSender;
import hello.suribot.interfaces.IAPIController;
import hello.suribot.interfaces.IIntentsAnalyzer;
import hello.suribot.interfaces.IRecastBotConnectorSender;
import hello.suribot.interfaces.IResponseGenerator;
import hello.suribot.response.Response;
import hello.suribot.response.ResponseGenerator;

/**
 * Classe d'analyse des données fournies par un moteur d'intelligence (qui a analysé la demande d'un utilisateur), 
 * dans le but de fournir une réponse adaptée à la demande de l'utilisateur (par exemple: des informations bancaires, d'assurance, etc...).
 */
public class IntentsAnalyzer implements IIntentsAnalyzer{
	
	private static final Logger logger = LogManager.getLogger();
	
	public static final String SUCCESS = "success";
	public static final String MISSINGPARAMS = "missingparams";
	
	// voir les intents de l'AI
	public static final String CONTRAT = "contrat";
	
	private IRecastBotConnectorSender nextToCall;
	
	private IResponseGenerator responsegenerator;
	private IAPIController apicontroller;
	
	
	public IntentsAnalyzer() {
		this.responsegenerator = new ResponseGenerator();
		this.apicontroller = new APIController();
		this.nextToCall = new RecastBotConnectorSender();
	}


	/* (non-Javadoc)
	 * @see hello.suribot.analyze.IIntentsAnalyzer#analyzeIntents(org.json.JSONObject, org.json.JSONObject, java.lang.String, boolean)
	 */
	@Override
	public void analyzeIntents(JSONObject mbc_json, JSONObject recastJson, String idUser, boolean firstTraitement) {
		String contexte = null;
		String language = null;
		JSONObject entities = null;
		boolean isChoice = false;
		try{
			
			if(firstTraitement){
				contexte = getContext(recastJson);
				entities = getEntities(recastJson);
				language = getLanguage(recastJson);
			} else {
				contexte = recastJson.getString(JSONMemory.CONTEXTE);
				entities = recastJson.getJSONObject(JSONMemory.ENTITIES);
				language = recastJson.getString(JSONMemory.LANGUAGE);
			}
			
			//Par défaut la langue du bot est le français, si la langue détectée n'est pas le français alors 
			//on charge un autre fichier de properties.
			if( !language.equals("fr") ) this.responsegenerator = new ResponseGenerator();
			Response responseToMBC = null;
			boolean demandeComprise = false;
			
			if(contexte != null && contexte.equals(CONTRAT)){
				
				//Traitement pour l'api lab-bot-api
				IContractAnalyzer analyzer = new ContractAnalyzer();

				JSONObject js = analyzer.analyze(entities, idUser);
				if(js.getBoolean(SUCCESS)){
					JSONMemory.removeLastEntities(idUser);
					String rep;
					
					try {
						rep = apicontroller.send(js.getString(ApiUrls.URITOCALL.name()));
					} catch (IOException e) {
						logger.error("APIController : Message with url \""+js.getString(ApiUrls.URITOCALL.name())+"\" not send... ("+e+")");
						rep = null;
					}
					isChoice=analyzer.isChoice();
					
					if(rep == null  || rep.isEmpty()) responseToMBC = responsegenerator.generateInternalErrorMessage();
					else responseToMBC = responsegenerator.generateUnderstoodMessage(CONTRAT, analyzer.getCalledMethod().toString(), isChoice, rep);
					demandeComprise = true;
					
				}else if(js.has(MISSINGPARAMS)){
					try {
						List<MissingAnalyzerParam> missingParams = new ArrayList<>(js.getJSONArray(MISSINGPARAMS).length());
						for(Object oneMissingParam : js.getJSONArray(MISSINGPARAMS)){
							missingParams.add(MissingAnalyzerParam.valueOf(oneMissingParam.toString()));
						}
						if(missingParams.size()==1){
							responseToMBC = responsegenerator.generateMessageButMissOneArg(missingParams.get(0));
						} else if(missingParams.size()>1){
							responseToMBC = responsegenerator.generateMessageButMissArgs(missingParams);
						}
					} catch (Exception e){}
				
				}
			} else {
				// demande incomprise;
				// Traitement si on a d'autre contextes : transport, maps, ...
			}
			
			//On a pas réussi à traiter la demande l'utilisateur, on essaye de la compléter avec l'ancienne demande
			if(!demandeComprise){
				if(firstTraitement){
					String stringLastIntent = JSONMemory.getLastEntities(idUser);
					if(stringLastIntent==null || stringLastIntent.isEmpty()){
						// Demande incomprise et il n'y a pas d'ancienne demande en attente, 
						// donc on arrete le traitement  et envoie une erreur a SS5
						recastJson = getEntities(recastJson);
						JSONMemory.putLastEntities(idUser, recastJson.toString());
						if(contexte != null && !contexte.isEmpty()) JSONMemory.putContext(idUser, contexte);
						if(responseToMBC==null || responseToMBC.getMessage()==null || responseToMBC.getMessage().isEmpty()){
							nextToCall.sendMessage(mbc_json, responsegenerator.generateNotUnderstoodMessage());
							return;
						} else {
							nextToCall.sendMessage(mbc_json, responseToMBC);
							return;
						}
					}
					//On essaye de completer l'ancienne demande présente avec les nouvelles données reçues
					JSONObject lastIntent = new JSONObject(stringLastIntent);
					JSONObject newRequest = generateNewRequestWithLastEntities(recastJson, lastIntent);
					JSONMemory.removeLastEntities(idUser);
					if(contexte != null && !contexte.isEmpty()) newRequest.put(JSONMemory.CONTEXTE, contexte);
					analyzeIntents(mbc_json, newRequest, idUser, false);
				}else{
					// Ce n'est pas le premier traitement de la demande de l'utilisateur
					
					// Demande incomprise et il n'y a pas d'ancienne demande en attente, 
					// donc on arrete le traitement  et envoie une erreur a SS5
					JSONMemory.putLastEntities(idUser, entities.toString());
					JSONMemory.putContext(idUser, contexte);
					if(responseToMBC==null || responseToMBC.getMessage()==null || responseToMBC.getMessage().isEmpty()) responseToMBC = responsegenerator.generateNotUnderstoodMessage();
					nextToCall.sendMessage(mbc_json, responseToMBC);
					return;
				}
			} else { // demande comprise
				if(firstTraitement && isChoice){
					//si la demande est un choix on stocke la demande pour y ajouter eventuellement 
					entities = getEntities(recastJson);
					if(entities!=null) JSONMemory.putLastEntities(idUser, entities.toString());
					JSONMemory.putContext(idUser, contexte);
				}else{
					JSONMemory.removeLastEntities(idUser);
					JSONMemory.removeLastContext(idUser);
				}
					
				if(responseToMBC==null || responseToMBC.getMessage()==null || responseToMBC.getMessage().isEmpty()){
					nextToCall.sendMessage(mbc_json, responsegenerator.generateNotUnderstoodMessage());
				} else {
					nextToCall.sendMessage(mbc_json, responseToMBC);
				}
			}
		}catch(JSONException e){
			if(firstTraitement){
				//Il y a eu une exception lors de la lecture de la recuperation du contexte,
				//on essaye de completer une ancienne demande avec les nouvelles données
				String stringLastEntities = JSONMemory.getLastEntities(idUser);
				if(stringLastEntities==null || stringLastEntities.isEmpty()){
					//Demande incomprise et il n'y a pas d'ancienne demande en attente
					//donc on arrete le traitement  et envoie une erreur a SS5
					JSONMemory.putLastEntities(idUser, recastJson.toString());
					Response responseToMBC = responsegenerator.generateNotUnderstoodMessage();
					nextToCall.sendMessage(mbc_json, responseToMBC);
					return;
				}
				JSONObject lastEntities = new JSONObject(stringLastEntities);
				JSONObject newRequest = generateNewRequestWithLastEntities(recastJson, lastEntities);
				JSONMemory.removeLastEntities(idUser);
				analyzeIntents(mbc_json, newRequest, idUser, false);
			}else{
				//Demande incomprise et il n'y a pas d'ancienne demande en attente
				//donc on arrete le traitement  et envoie une erreur a SS5
				if(entities != null) JSONMemory.putLastEntities(idUser, entities.toString());
				JSONMemory.putContext(idUser, contexte);
				Response responseToMBC = responsegenerator.generateNotUnderstoodMessage();
				nextToCall.sendMessage(mbc_json, responseToMBC);
			}
		}
	}
	
	/**
	 * Forme une nouvelle demande en combinant la précédente (si existante dans les fichiers ".json", voir {@link JSONMemory}) 
	 * et la nouvelle
	 * @param newDemande
	 * @param lastEntities
	 * @return
	 */
	private static JSONObject generateNewRequestWithLastEntities(JSONObject newDemande, JSONObject lastEntities){
		String langue = null;
		try{
			langue = getLanguage(newDemande);
			newDemande = getEntities(newDemande);
		}catch(JSONException e){
			return lastEntities;
		}
		JSONObject js = new JSONObject(lastEntities.toString());
		for(String key: newDemande.keySet()){
			//on insere les nouvelles données de la demande à la derniere demande incomprise
			//pour essayer de la completer
			js.put(key, newDemande.get(key));
		}
		lastEntities = new JSONObject();
		lastEntities.put(JSONMemory.ENTITIES, js);
		lastEntities.put(JSONMemory.LANGUAGE, langue);
		return lastEntities;
	}
	
	private static String getContext(JSONObject recastJson){
		JSONObject jsonResult = null;
		if(recastJson != null){
			try{
				jsonResult = new JSONObject();
				jsonResult = (JSONObject) recastJson.get(SuribotKeys.RESULTS.value);
				JSONArray ja = (JSONArray) jsonResult.get(SuribotKeys.INTENTS.value);
				if(ja != null) return ja.getJSONObject(0).getString(SuribotKeys.SLUG.value);
			}catch(JSONException e){
				return null;
			}
		}
		return null;
	}
	
	private static JSONObject getEntities(JSONObject recastJson){
		JSONObject jsonResult = new JSONObject();
		if(recastJson != null){
			jsonResult = (JSONObject) recastJson.get(SuribotKeys.RESULTS.value);
			jsonResult = (JSONObject) jsonResult.get(SuribotKeys.ENTITIES.value);
			return jsonResult;
		}
		return null;
	}
	
	private static String getLanguage(JSONObject recastJson){
		JSONObject jsonResult = new JSONObject();
		if(recastJson != null){
			jsonResult = (JSONObject) recastJson.get(SuribotKeys.RESULTS.value);
			return jsonResult.getString(SuribotKeys.LANGUAGE.value);
		}
		return null;
	}
}