/**
 * Copyright (c) Codice Foundation
 *
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 *
 **/
/*global define*/
var Marionette = require('marionette');
var template = require('./list-interactions.hbs');
var CustomElements = require('js/CustomElements');
var MenuNavigationDecorator = require('decorator/menu-navigation.decorator');
var Decorators = require('decorator/Decorators');

module.exports = Marionette.ItemView.extend(Decorators.decorate({
    template: template,
    tagName: CustomElements.register('list-interactions'),
    className: 'is-action-list',
    events: {
        'click .interaction-run': 'handleRun',
        'click .interaction-stop': 'handleCancel',
        'click .interaction-delete': 'handleDelete',
        'click .interaction-duplicate': 'handleDuplicate',
        'click': 'handleClick'
    },
    initialize: function(){
        if (!this.model.get('query').get('result')) {
            this.startListeningToSearch();
        }
        this.handleResult();
    },
    startListeningToSearch: function(){
        this.listenToOnce(this.model.get('query'), 'change:result', this.startListeningForResult);
    },
    startListeningForResult: function(){
        this.listenToOnce(this.model.get('query').get('result'), 'sync error', this.handleResult);
    },
    handleRun: function(){
        this.model.get('query').startSearch();
    },
    handleCancel: function(){
        this.model.get('query').cancelCurrentSearches();
    },
    handleDelete: function(){
        this.model.collection.remove(this.model);
    },
    handleDuplicate: function(){
        var copyAttributes = JSON.parse(JSON.stringify(this.model.attributes));
        delete copyAttributes.id;
        delete copyAttributes.query;
        var newList = new this.model.constructor(copyAttributes);
        this.model.collection.add(newList);
    },
    handleResult: function(){
        this.$el.toggleClass('has-results', this.model.get('query').get('result') !== undefined);
    },
    handleClick: function(){
        this.$el.trigger('closeDropdown.'+CustomElements.getNamespace());
    }
}, MenuNavigationDecorator));