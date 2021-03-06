/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.am.management.service.impl;

import freemarker.cache.StringTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.template.Configuration;
import io.gravitee.am.management.service.EmailManager;
import io.gravitee.am.model.Email;
import io.reactivex.Completable;
import io.reactivex.Single;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import static java.lang.String.format;

/**
 * @author Titouan COMPIEGNE (titouan.compiegne at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class EmailManagerImpl implements EmailManager {

    private static final String TEMPLATE_SUFFIX = ".html";
    private ConcurrentMap<String, Email> emailTemplates = new ConcurrentHashMap<>();

    @Value("${email.from}")
    private String defaultFrom;

    @Value("${email.subject:[Gravitee.io] %s}")
    private String subject;

    @Autowired
    private TemplateLoader templateLoader;

    @Autowired
    private Configuration configuration;

    @Override
    public Single<Email> reloadEmail(Email email) {
        final String templateName = getTemplateName(email);
        if (email.isEnabled()) {
            reloadTemplate(templateName + TEMPLATE_SUFFIX, email.getContent());
            emailTemplates.put(templateName, email);
        } else {
            // remove email who has been disabled
            emailTemplates.remove(templateName);
            ((StringTemplateLoader) templateLoader).removeTemplate(templateName + TEMPLATE_SUFFIX);
        }
        return Single.just(email);
    }

    @Override
    public Completable deleteEmail(String email) {
        Optional<Email> emailOptional = emailTemplates.values().stream().filter(email1 -> email.equals(email1.getId())).findFirst();
        if (emailOptional.isPresent()) {
            Email emailToRemove = emailOptional.get();
            emailTemplates.remove(getTemplateName(emailToRemove));
            ((StringTemplateLoader) templateLoader).removeTemplate(getTemplateName(emailToRemove) + TEMPLATE_SUFFIX);
        }
        return Completable.complete();
    }

    @Override
    public Email getEmail(String template, String defaultSubject, int defaultExpiresAfter) {
        boolean templateFound = emailTemplates.containsKey(template);
        String[] templateParts = template.split(Pattern.quote(TEMPLATE_NAME_SEPARATOR));

        // template not found for the client, try at domain level
        if (!templateFound && templateParts.length == 3) {
            template = templateParts[0] + TEMPLATE_NAME_SEPARATOR + templateParts[1];
            templateFound = emailTemplates.containsKey(template);
        }

        if (templateFound) {
            Email customEmail = emailTemplates.get(template);
            return create(template, customEmail.getFrom(), customEmail.getFromName(), customEmail.getSubject(), customEmail.getExpiresAfter());
        } else {
            // template not found, return default template
            template = templateParts[0];
            return create(template, defaultFrom, null, format(subject, defaultSubject), defaultExpiresAfter);
        }
    }

    private Email create(String template, String from, String fromName, String subject, int expiresAt) {
        Email email = new Email();
        email.setTemplate(template);
        email.setFrom(from);
        email.setFromName(fromName);
        email.setSubject(subject);
        email.setExpiresAfter(expiresAt);
        return email;
    }

    private void reloadTemplate(String templateName, String content) {
        ((StringTemplateLoader) templateLoader).putTemplate(templateName, content, System.currentTimeMillis());
        configuration.clearTemplateCache();
    }

    private String getTemplateName(Email email) {
        return email.getTemplate()
                + TEMPLATE_NAME_SEPARATOR
                + email.getDomain()
                + ((email.getClient() != null) ? TEMPLATE_NAME_SEPARATOR + email.getClient() : "");
    }

    public void setDefaultFrom(String defaultFrom) {
        this.defaultFrom = defaultFrom;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }
}
