package com.revature.services;

import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.revature.events.FlashcardChangeEvent;
import com.revature.repositories.FlashcardRepository;

@Service
public class MessageService {
	
	private static Set<Integer> eventCache = new HashSet<>();
	
	@Autowired
	private FlashcardRepository flashcardDao;

	@Autowired
	private KafkaTemplate<String, FlashcardChangeEvent> kt;
	
	public void triggerEvent(FlashcardChangeEvent event) {
		eventCache.add(event.hashCode());
		
		kt.send("flashcard", event);
	}
	
	@KafkaListener(topics = "flashcard")
	public void processEvent(FlashcardChangeEvent event) {
		if(eventCache.contains(event.hashCode())) {
			eventCache.remove(event.hashCode());
			return;
		}
		
		switch(event.getOperation()) {
		case CREATE:
		case UPDATE:
			flashcardDao.save(event.getFlashcard());
			break;
		case DELETE:
			flashcardDao.delete(event.getFlashcard());
			break;
		}
	}
}
