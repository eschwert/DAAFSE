(function(angular){ 'use strict';
    var module = angular.module('metersApp.meters', [
        'metersApp.meters.services', 'ngRDFResource', 'ngSPARQL', 
        'ngSPARQL.config', 'ngWAMP'
    ]);
    
    module.controller('MeterListCtrl', function($scope, ResourceManager) {
        $scope.substations = {};
        $scope.activePanel = -1;

        ResourceManager.findByType('em:Mercury230', [
            'em:hasSerialNumber', 'dul:hasLocation/rdfs:label', 
            'rdf:type/rdfs:label', 'ssn:hasDeployment/rdfs:label'
        ])
        .then(function(meters) {
            meters.forEach(function(meter) {
                var substation = meter.get('ssn:hasDeployment/rdfs:label');
                if(!$scope.substations[substation]) {
                    $scope.substations[substation] = [];
                }
                $scope.substations[substation].push(meter);
            });
        });
    });
    
    module.controller('MeterPageCtrl', function(
            $scope, $routeParams, ResourceManager) {
        ResourceManager.findByURI($routeParams.meterUri, [
            'dul:hasLocation/rdfs:label', 'em:hasSerialNumber', 'rdf:type/rdfs:label'
        ]).then(function(meters) {
            $scope.meter = meters[0];
        });
    });
    module.controller('MeterChartCtrl', function($scope, $routeParams, $q, 
        ResourceManager, GraphFactory, utils, wamp, metersService, sparql) {
        var thisArg = this;
        var sub;
        $scope.vChartConfig = {
            options: {
                chart: { type: 'line', zoomType: 'x'},
                rangeSelector: {
                    inputEnabled: false,
                    enabled: true,
                    buttons: [
                        {
                            type: 'minute',
                            count: 60,
                            text: '1h'
                        },
                        {
                            type: 'day',
                            count: 1,
                            text: '1d'
                        },
                        {
                            type: 'all',
                            text: 'All'
                        }
                    ]
                }
            },
            useHighStocks: true,
            series: [
                { name: 'Фаза 1', data: [] },
                { name: 'Фаза 2', data: [] },
                { name: 'Фаза 3', data: [] }
            ],
            legend: { enabled: true },
            yAxis: { title: { text: 'Voltage (V)' } },
            xAxis: { type: 'datetime', minRange: 15*60000 },
            loading: true
        };
        $scope.pChartConfig = {
            options: {
                chart: { type: 'line', zoomType: 'x'},
                rangeSelector: {
                    inputEnabled: false,
                    enabled: true,
                    buttons: [
                        {
                            type: 'minute',
                            count: 60,
                            text: '1h'
                        },
                        {
                            type: 'day',
                            count: 1,
                            text: '1d'
                        },
                        {
                            type: 'all',
                            text: 'All'
                        }
                    ]
                }
            },
            useHighStocks: true,
            series: [
                { name: 'Фаза 1', data: [] },
                { name: 'Фаза 2', data: [] },
                { name: 'Фаза 3', data: [] }
            ],
            legend: { enabled: true },
            yAxis: { title: { text: 'Electric Power (kW)' } },
            xAxis: { type: 'datetime', minRange: 15*60000 },
            loading: true
        };
        $scope.typeToChart = {
            "http://purl.org/NET/ssnext/electricmeters#PolyphasePowerObservation" : $scope.pChartConfig,
            "http://purl.org/NET/ssnext/electricmeters#PolyphaseVoltageObservation" : $scope.vChartConfig
        };
        $scope.fromDate = new Date();
        $scope.fromTime = $scope.fromDate.getTime() - 3600000; //minus an hour
        $scope.untilTime = null;
        $scope.types = [];
        
        ResourceManager.findByURI($routeParams.meterUri, ['em:hasStream'])
        .then(function(meters) {
            return $scope.meter = meters[0];
        })
        .then(function(meter) {
            return sparql.select(
                "SELECT ?type {<" + meter.uri + "> ssn:observes ?prop .?type ssn:observedProperty ?prop .}");
        })
        .then(function(typesUris) {
            var promises = [];
            typesUris.forEach(function(type) {
                $scope.types.push(type.type);
                var promise = thisArg._loadObservationToChart(
                        $scope.typeToChart[type.type], $scope.meter, type.type, 
                        toZeroTimeDate($scope.fromDate, $scope.fromTime),
                        toZeroTimeDate($scope.fromDate, $scope.untilTime));
                promises.push(promise);
            });
            return $q.all(promises);
        })
        .then(function() {
            sub = wamp.subscribe($scope.meter.get('em:hasStream'), 
                thisArg._onMessage);
        });
        
        $scope._addObservation = function(chart, observation, length) {
            utils.shiftAndPush(chart.series[0].data, 
                observation[1], length);
            utils.shiftAndPush(chart.series[1].data, 
                        observation[2], length);
            utils.shiftAndPush(chart.series[2].data,
                        observation[3], length);
        };
        $scope._removeObservations = function(chart) {
            chart.series[0].data = [];
            chart.series[1].data = [];
            chart.series[2].data = [];
        };
        $scope.changedDateRange = function() {
            var promises = [];
            $scope.types.forEach(function(typeUri){
                var promise = thisArg._loadObservationToChart(
                        $scope.typeToChart[typeUri], $scope.meter, typeUri, 
                        toZeroTimeDate($scope.fromDate, $scope.fromTime),
                        toZeroTimeDate($scope.fromDate, $scope.untilTime));
                promises.push(promise);
            });
            var promise = $q.all(promises);
            if(!$scope.untilTime) {
                promise.then(function() {
                    sub = wamp.subscribe($scope.meter.get('em:hasStream'), 
                        thisArg._onMessage);
                });
            }
        };
        $scope.$on('$destroy', function() {
            if(sub) {
                sub.then(function(subscription) {
                    subscription.unsubscribe();
                });
            }
        });
        
        this._onMessage = function(args) {
            utils.parseTTL(args[0])
            .then(GraphFactory.newFromTriples)
            .then(function(graph) {
                var points = [];
                var observation = graph.getByType('em:PolyphaseVoltageObservation')[0] ||
                        graph.getByType('em:PolyphasePowerObservation')[0];
                var output = graph.getByURI(observation.get('ssn:observationResult'));
                output['ssn:hasValue'].forEach(function(valueURI){
                    var value = graph.getByURI(valueURI);
                    var phaseNumber = value.get('em:hasPhaseNumber');
                    points[phaseNumber] = [
                        new Date(observation.get('ssn:observationResultTime')).getTime(),
                        parseFloat(value.get('em:hasQuantityValue'))
                    ];
                });
                
                if(observation.is('em:PolyphaseVoltageObservation')) {
                    $scope._addObservation($scope.vChartConfig, points);
                } else {
                    $scope._addObservation($scope.pChartConfig, points);
                }
            });
        };
        this._loadObservations = function(meter, from, till) {
            $scope._removeObservations($scope.vChartConfig);
            $scope._removeObservations($scope.pChartConfig);
            
            $scope.vChartConfig.loading = true;
            $scope.pChartConfig.loading = true;
            
            return metersService.fetchObservations(meter.uri, from, till, 
                ['em:PolyphaseVoltageObservation', 'em:PolyphasePowerObservation'])
                .then(function(points) {
                    Object.getOwnPropertyNames(points).forEach(function(type) {
                        if(type === 'em:PolyphaseVoltageObservation') {
                            utils.addPoints($scope.vChartConfig, points[type]);
                        } else {
                            utils.addPoints($scope.pChartConfig, points[type]);
                        }
                    });
                })
                .then(function() {
                    $scope.vChartConfig.loading = false;
                    $scope.pChartConfig.loading = false;
                });
        };
        this._loadObservationToChart = function(chart, meter, typeUri, from, till) {
            $scope._removeObservations(chart);
            chart.loading = true;
            
            return metersService.fetchObservation(meter.uri, typeUri, from, till)
            .then(function(points) {
                utils.addPoints(chart, points);
            })
            .then(function() {
                chart.loading = false;
            });
        };
    });
    
    function toZeroTimeDate(date, time) {
        if(date) {
            var t = new Date(time);
            return time ?
                new Date(
                    date.getFullYear(), date.getMonth(), date.getDate(),
                    t.getHours(), t.getMinutes(), 0, 0) : null;
        } else {
            return null;
        }
    };
    
})(window.angular);